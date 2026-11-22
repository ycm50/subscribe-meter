package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.makeWindow
import com.ycm50.subscribemeter.net.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * one-api / new-api / sub2api 网关额度(自建或中转站)。
 *
 * A. one-api / new-api(同源):
 *    GET {base}/v1/dashboard/billing/subscription → { hard_limit_usd }  总额 = 剩余 + 已用
 *    GET {base}/v1/dashboard/billing/usage        → { total_usage }     已用 × 100(美分制)
 *    GET {base}/api/status                        → 展示单位
 *    GET {base}/api/user/self                     → { quota, used_quota }(系统访问令牌兜底)
 *    业务错误同样返回 200 + body.error,因此必须看字段而不是只看状态码。
 *
 * B. sub2api:GET {base}/v1/usage,三种形态(quota 额度型 / subscription 订阅型 / 钱包型)。
 *
 * gatewayKind 记住探测结果,下次直接按类型查;该路径 404 时自动重探测。
 */
class NewApiProvider(private val http: NetworkClient) : QuotaProvider {

    override suspend fun fetch(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        val base = normalizeBase(cfg.baseUrl)
            ?: return Snapshot.fail(id, "站点地址无效:请填 http(s)://主机[:端口][/路径]", "网关")
        if (ctx.secret.isEmpty()) {
            return Snapshot.soft(id, "未配置访问令牌:填面板「系统访问令牌」或 sk-* Key(设置 → 该额度源 → 密钥)", base)
        }
        val raw = when (cfg.gatewayKind) {
            "oneapi" -> fetchOneApi(id, base, ctx.secret) ?: fetchDetected(id, base, ctx.secret)
            "sub2api" -> fetchSub2Api(id, base, ctx.secret) ?: fetchDetected(id, base, ctx.secret)
            else -> fetchDetected(id, base, ctx.secret)
        }
        // 网关返回的额度可能以 tokens / 站点自定义单位计;填了美元总额就统一折成 USD。
        return raw.copy(windows = com.ycm50.subscribemeter.core.UsdCaps.apply(raw.windows, cfg.windowUsdCaps))
    }

    /** 自动探测:先 one-api 计费端点,再 sub2api 的 /v1/usage。 */
    private suspend fun fetchDetected(id: String, base: String, key: String): ProviderSnapshot {
        fetchOneApi(id, base, key)?.let { if (it.windows.isNotEmpty()) return it }
        fetchSub2Api(id, base, key)?.let { if (it.windows.isNotEmpty()) return it }
        return Snapshot.fail(id, "该站点既不是 one-api/new-api 也不像 sub2api:请确认地址与令牌权限", base)
    }

    /** one-api / new-api:计费端点优先,失败回退 /api/user/self。null = 此类型不成立。 */
    private suspend fun fetchOneApi(id: String, base: String, key: String): ProviderSnapshot? {
        val headers = authHeaders(key)
        val unit = runCatching {
            val statusResult = http.get(base + "/api/status", emptyMap(), timeoutMs = 8000)
            if (statusResult.ok) parseUnit(statusResult.body) else ""
        }.getOrDefault("")

        val subResult = runCatching { http.get(base + "/v1/dashboard/billing/subscription", headers) }.getOrNull()
        if (subResult != null && subResult.status in 401..403) {
            return Snapshot.soft(id, "令牌无权访问(HTTP " + subResult.status + "),或站点要求系统访问令牌", base)
        }
        if (subResult != null && subResult.ok) {
            val usageResult = runCatching { http.get(base + "/v1/dashboard/billing/usage", headers) }.getOrNull()
            if (usageResult != null && usageResult.ok) {
                val window = Parsers.newApiBilling(subResult.body, usageResult.body, unit)
                if (window != null) {
                    return Snapshot.ok(id, listOf(window), "new-api · 计费端点", metaOf(base, unit), base + "/v1/dashboard/billing/subscription")
                }
            }
        }

        val selfResult = runCatching { http.get(base + "/api/user/self", headers) }.getOrNull()
        if (selfResult != null && selfResult.ok) {
            val window = Parsers.newApiSelf(selfResult.body, unit)
            if (window != null) {
                return Snapshot.ok(id, listOf(window), "new-api · 用户信息", metaOf(base, unit), base + "/api/user/self")
            }
        }
        return null
    }

    /** sub2api:GET /v1/usage,三种形态都归一化成窗口。null = 此类型不成立。 */
    private suspend fun fetchSub2Api(id: String, base: String, key: String): ProviderSnapshot? {
        val result = runCatching { http.get(base + "/v1/usage", authHeaders(key)) }.getOrNull() ?: return null
        if (result.status in 401..403) {
            return Snapshot.soft(id, "令牌无权访问(HTTP " + result.status + ")", base)
        }
        if (!result.ok) return null
        val root = runCatching { Json.parseToJsonElement(result.body) as? JsonObject }.getOrNull() ?: return null
        val looksLike = listOf("mode", "planName", "quota", "remaining").any { root.containsKey(it) }
        if (!looksLike) return null

        val planName = root["planName"].asTextOrEmpty()
        val unit = root["unit"].asTextOrEmpty().ifEmpty { "USD" }
        val windows = mutableListOf<com.ycm50.subscribemeter.core.QuotaWindow>()

        (root["quota"] as? JsonObject)?.let { quota ->
            makeWindow(
                "monthly",
                "额度",
                used = quota["used"].asDoubleOrNull(),
                cap = quota["limit"].asDoubleOrNull(),
                unit = quota["unit"].asTextOrEmpty().ifEmpty { unit },
            )?.let { windows.add(it) }
        }
        for (element in (root["rate_limits"] as? JsonArray) ?: JsonArray(emptyList())) {
            val obj = element as? JsonObject ?: continue
            val slot = when (obj["window"].asTextOrEmpty()) {
                "5h" -> "rolling" to "5 小时"
                "7d" -> "weekly" to "本周"
                "1d" -> "weekly" to "本周"
                else -> continue
            }
            if (windows.any { it.id == slot.first }) continue
            makeWindow(
                slot.first,
                slot.second,
                used = obj["used"].asDoubleOrNull(),
                cap = obj["limit"].asDoubleOrNull(),
                unit = unit,
                resetsAt = obj["reset_at"],
            )?.let { windows.add(it) }
        }
        (root["subscription"] as? JsonObject)?.let { sub ->
            val pairs = listOf(
                Triple("rolling", "daily_usage_usd", "daily_limit_usd"),
                Triple("weekly", "weekly_usage_usd", "weekly_limit_usd"),
                Triple("monthly", "monthly_usage_usd", "monthly_limit_usd"),
            )
            val labels = mapOf("rolling" to "今日", "weekly" to "本周", "monthly" to "本月")
            for ((slot, usedKey, capKey) in pairs) {
                if (windows.any { it.id == slot }) continue
                makeWindow(
                    slot,
                    labels[slot] ?: slot,
                    used = sub[usedKey].asDoubleOrNull(),
                    cap = sub[capKey].asDoubleOrNull(),
                    unit = unit,
                )?.let { windows.add(it) }
            }
        }
        // 钱包型:只有余额,用累计花费反推「已用 / 总额」才能画进度条。
        if (windows.none { it.id == "monthly" }) {
            val remaining = root["remaining"].asDoubleOrNull()
            val spent = ((root["usage"] as? JsonObject)?.get("total") as? JsonObject)?.get("cost").asDoubleOrNull()
            if (remaining != null && spent != null) {
                makeWindow("monthly", "钱包", used = spent, cap = remaining + spent, unit = unit)?.let { windows.add(it) }
            }
        }
        if (windows.isEmpty()) return null
        val subtitle = if (planName.isEmpty()) "sub2api" else "sub2api · " + planName
        return Snapshot.ok(id, windows, subtitle, metaOf(base, unit), base + "/v1/usage")
    }

    private fun metaOf(base: String, unit: String): Map<String, String> = buildMap {
        put("站点", base)
        if (unit.isNotEmpty()) put("单位", unit)
    }

    private fun authHeaders(key: String): Map<String, String> = mapOf("Authorization" to "Bearer " + key)

    /** 规范化站点地址:去尾部斜杠与 /v1 后缀,保留子路径(如 https://host/newapi)。 */
    private fun normalizeBase(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "https://" + text
        val uri = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        var path = (uri.path ?: "").trimEnd('/')
        path = path.replace(Regex("/v\\d+$", RegexOption.IGNORE_CASE), "")
        val port = if (uri.port > 0) ":" + uri.port else ""
        return scheme + "://" + host + port + path
    }

    /** /api/status 的展示单位;老版本只有 display_in_currency 布尔。 */
    private fun parseUnit(body: String): String {
        val root = Parsers.unwrap(body) ?: return ""
        val type = root["quota_display_type"].asTextOrEmpty().uppercase()
        if (type == "USD" || type == "CNY") return type
        if (type == "TOKENS") return "tokens"
        return when (root["display_in_currency"].asTextOrEmpty()) {
            "true" -> "USD"
            "false" -> "tokens"
            else -> ""
        }
    }
}