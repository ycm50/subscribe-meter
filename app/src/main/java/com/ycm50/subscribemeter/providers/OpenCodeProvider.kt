package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.makeWindow
import com.ycm50.subscribemeter.data.OpenCodeMode
import com.ycm50.subscribemeter.net.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * OpenCode 额度:两种口径。
 *
 * 1) Go 订阅(GET {base}/zen/go/v1/usage,Bearer = OPENCODE_GO_API_KEY):
 *    { usage: { rolling|weekly|monthly: { status, percent, resetsAt } } }
 *    只有百分比,没有美元字段 —— 因此不做任何金额折算。
 *    status = 'rate-limited' 是正常业务态(限流中),不是错误。
 *
 * 2) Zen 预付费余额(控制台 Cookie,非 API Key):
 *    GET {base}/console/api/orgs           → 工作区列表(cookie 认证)
 *    GET {base}/console/api/billing/status → balanceMicroCents / 100000000 = USD(x-org-id 必填)
 */
class OpenCodeProvider(private val http: NetworkClient) : QuotaProvider {

    override suspend fun fetch(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        return when (OpenCodeMode.of(cfg.openCodeMode)) {
            OpenCodeMode.ZEN -> fetchZen(ctx)
            OpenCodeMode.GO -> fetchGo(ctx)
        }
    }

    private suspend fun fetchGo(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        if (ctx.secret.isEmpty()) {
            return Snapshot.soft(id, "未配置 OpenCode API Key:有 Go 订阅时填入 OPENCODE_GO_API_KEY(设置 → 该额度源 → 密钥)", "Go 订阅")
        }
        val base = cfg.openCodeBaseUrl.trim().trimEnd('/').ifEmpty { "https://opencode.ai" }
        val url = base + "/zen/go/v1/usage"
        val result = runCatching { http.get(url, mapOf("Authorization" to "Bearer " + ctx.secret)) }
            .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "Go 订阅", url) }
        if (result.status == 401 || result.status == 403) {
            return Snapshot.soft(id, "没有检测到生效的 OpenCode Go 订阅(HTTP " + result.status + "),或 API Key 无效", "Go 订阅")
        }
        if (!result.ok) return Snapshot.fail(id, "额度接口返回 HTTP " + result.status, "Go 订阅", url)
        val raw = Parsers.openCodeGo(result.body)
        if (raw.isEmpty()) {
            return Snapshot.fail(id, "响应里没有可解析的用量窗口(rolling / weekly / monthly),接口结构可能已变化", "Go 订阅", url)
        }
        // 接口只给百分比;用户在设置里填了「额度总额(美元)」就补出 used/cap 并从 % 反算金额。
        val windows = com.ycm50.subscribemeter.core.UsdRatio.apply(
            raw,
            cfg.windowUsdCaps,
            listOf("rolling", "weekly", "monthly"),
        )
        val meta = buildMap<String, String> {
            if (windows.any { it.unit == "USD" }) put("口径", "按填写的档位比例推导")
        }
        return Snapshot.ok(id, windows, "Go 订阅", meta, url)
    }

    private suspend fun fetchZen(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        if (ctx.secret.isEmpty()) {
            return Snapshot.soft(id, "Zen 余额走控制台 Cookie:浏览器登录 opencode.ai 后复制 __Host-console_session 等 Cookie 填入", "Zen 余额")
        }
        val base = cfg.openCodeBaseUrl.trim().trimEnd('/').ifEmpty { "https://opencode.ai" }
        val cookieHeaders = mapOf("Cookie" to ctx.secret)

        var orgId = cfg.orgId.trim()
        if (orgId.isEmpty()) {
            val orgsUrl = base + "/console/api/orgs"
            val orgsResult = runCatching { http.get(orgsUrl, cookieHeaders) }
                .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "Zen 余额", orgsUrl) }
            if (orgsResult.status == 401 || orgsResult.status == 403) {
                return Snapshot.soft(id, "控制台 Cookie 已失效(HTTP " + orgsResult.status + "),请重新登录 opencode.ai 复制 Cookie", "Zen 余额")
            }
            if (!orgsResult.ok) return Snapshot.fail(id, "工作区列表返回 HTTP " + orgsResult.status, "Zen 余额", orgsUrl)
            orgId = firstOrgId(orgsResult.body)
            if (orgId.isEmpty()) {
                return Snapshot.fail(id, "未能从 /console/api/orgs 解析出工作区 id,可在设置里手动填写", "Zen 余额", orgsUrl)
            }
        }

        val billingUrl = base + "/console/api/billing/status"
        val billingResult = runCatching { http.get(billingUrl, cookieHeaders + mapOf("x-org-id" to orgId)) }
            .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "Zen 余额", billingUrl) }
        if (billingResult.status == 401 || billingResult.status == 403) {
            return Snapshot.soft(id, "控制台 Cookie 已失效(HTTP " + billingResult.status + ")", "Zen 余额")
        }
        if (!billingResult.ok) return Snapshot.fail(id, "余额接口返回 HTTP " + billingResult.status, "Zen 余额", billingUrl)

        val root = runCatching { Json.parseToJsonElement(billingResult.body) as? JsonObject }.getOrNull()
            ?: return Snapshot.fail(id, "余额响应不是合法 JSON", "Zen 余额", billingUrl)
        val microCents = root["balanceMicroCents"].asDoubleOrNull()
            ?: return Snapshot.fail(id, "响应里没有 balanceMicroCents,接口结构可能已变化", "Zen 余额", billingUrl)
        val usd = microCents / 100_000_000.0
        val window = makeWindow(
            id = "balance",
            label = "Zen 预付费余额",
            remaining = usd,
            percent = 0.0,
            unit = "USD",
            note = "预付费余额(无上限)",
        ) ?: return Snapshot.fail(id, "余额解析失败", "Zen 余额", billingUrl)
        val mode = root["mode"].asTextOrEmpty()
        val meta = buildMap {
            put("工作区", orgId)
            if (mode.isNotEmpty()) put("计费模式", mode)
        }
        return Snapshot.ok(id, listOf(window), "Zen 余额", meta, billingUrl)
    }

    /** /console/api/orgs 可能是数组,或包在 orgs / data / workspaces 下,都兜住。 */
    private fun firstOrgId(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return ""
        val list: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["orgs"] as? JsonArray)
                ?: (root["data"] as? JsonArray)
                ?: (root["workspaces"] as? JsonArray)
                ?: JsonArray(emptyList())

            else -> JsonArray(emptyList())
        }
        for (element in list) {
            val obj = element as? JsonObject ?: continue
            val value = obj["id"].asTextOrEmpty().ifEmpty { obj["orgId"].asTextOrEmpty() }.ifEmpty { obj["workspaceId"].asTextOrEmpty() }
            if (value.isNotEmpty()) return value
        }
        return ""
    }
}