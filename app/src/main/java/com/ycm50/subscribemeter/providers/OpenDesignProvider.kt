package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.net.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * OpenDesign Cloud 额度(Vela 登录态 controlKey,不是推理 Key)。
 *
 * 两步查询:
 *  1) GET https://amr-api.open-design.ai/api/v1/workspaces/current → workspaceId(可手填覆盖)
 *  2) GET .../api/v1/workspaces/{ws}/billing/funding-check[?modelId=]
 *     → { balanceUsd, codingPlan: { tier, windows: [
 *          { durationSeconds: 18000, usedCredits, limitCredits, resetsAt },   // 5 小时
 *          { durationSeconds: 604800, usedCredits, limitCredits, resetsAt }   // 7 天
 *        ] } }
 *
 * 单位:窗口原始单位是 Design Plan credits。用户可以在设置里填「总额(美元)」+ 这个总额
 * 对应哪个窗口(默认 7 天),插件据此推出汇率 = 该窗口 limitCredits ÷ 总额,
 * 然后把所有窗口(含 5 小时)按同一汇率折算成 USD 显示 —— 例如 15000 credits 记作 $30,
 * 5 小时额度就按同一汇率换算。留空则原样显示 credits。
 */
class OpenDesignProvider(private val http: NetworkClient) : QuotaProvider {

    override suspend fun fetch(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        if (ctx.secret.isEmpty()) {
            return Snapshot.soft(id, "未配置 OpenDesign 登录态:跑一次 vela-cli login 后复制 controlKey(不是 sk-* 推理 Key)", "Cloud")
        }
        val headers = mapOf("Authorization" to "Bearer " + ctx.secret)
        var workspaceId = cfg.workspaceId.trim()
        var tier = ""

        if (workspaceId.isEmpty()) {
            val wsUrl = ORIGIN + "/api/v1/workspaces/current"
            val wsResult = runCatching { http.get(wsUrl, headers) }
                .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "Cloud", wsUrl) }
            if (wsResult.status == 401 || wsResult.status == 403) {
                return Snapshot.soft(id, "登录态失效(HTTP " + wsResult.status + "),请重新登录 Vela 获取 controlKey", "Cloud")
            }
            if (!wsResult.ok) return Snapshot.fail(id, "工作区接口返回 HTTP " + wsResult.status, "Cloud", wsUrl)
            val wsRoot = runCatching { Json.parseToJsonElement(wsResult.body) as? JsonObject }.getOrNull()
                ?: return Snapshot.fail(id, "工作区响应不是合法 JSON", "Cloud", wsUrl)
            workspaceId = wsRoot["workspaceId"].asTextOrEmpty()
            tier = wsRoot["personalMembershipTier"].asTextOrEmpty()
            if (workspaceId.isEmpty()) {
                return Snapshot.fail(id, "未能解析出 workspaceId,可在设置里手动填写", "Cloud", wsUrl)
            }
        }

        val fundingUrl = ORIGIN + "/api/v1/workspaces/" + workspaceId + "/billing/funding-check"
        val result = runCatching { http.get(fundingUrl, headers) }
            .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "Cloud", fundingUrl) }
        if (result.status == 401 || result.status == 403) {
            return Snapshot.soft(id, "登录态失效(HTTP " + result.status + ")", "Cloud")
        }
        if (!result.ok) return Snapshot.fail(id, "额度接口返回 HTTP " + result.status, "Cloud", fundingUrl)

        val root = runCatching { Json.parseToJsonElement(result.body) as? JsonObject }.getOrNull()
            ?: return Snapshot.fail(id, "额度响应不是合法 JSON", "Cloud", fundingUrl)
        if (tier.isEmpty()) tier = ((root["codingPlan"] as? JsonObject)?.get("tier")).asTextOrEmpty()
        val rawWindows = Parsers.openDesign(result.body)
        if (rawWindows.isEmpty()) {
            return Snapshot.fail(id, "响应里没有可用的 codingPlan.windows,接口结构可能已变化", "Cloud", fundingUrl)
        }
        // 原有「总额 + 对应窗口」设置,和新的「按窗口填总额」合并成同一份映射后统一推导。
        val caps = cfg.windowUsdCaps.toMutableMap()
        if (cfg.openDesignUsdPool > 0) caps[cfg.openDesignUsdPoolWindow] = cfg.openDesignUsdPool
        val windows = com.ycm50.subscribemeter.core.UsdRatio.apply(
            rawWindows,
            caps,
            listOf("5h", "7d"),
        )
        val subtitle = if (tier.isEmpty()) "Cloud" else "Cloud · " + tier
        val meta = buildMap<String, String> {
            put("工作区", workspaceId)
            root["balanceUsd"].asDoubleOrNull()?.let { put("钱包", String.format("%.2f USD", it)) }
            val filled = caps.filterValues { it > 0 }
            if (filled.isNotEmpty()) {
                val anchorId = filled.keys.first()
                val anchor = rawWindows.firstOrNull { it.id == anchorId }
                if (anchor?.cap != null && anchor.cap > 0) {
                    put("汇率", String.format("%.0f credits = $1", anchor.cap / filled.getValue(anchorId)))
                }
            }
        }
        return Snapshot.ok(id, windows, subtitle, meta, fundingUrl)
    }

    companion object {
        const val ORIGIN = "https://amr-api.open-design.ai"

        /**
         * 由「某窗口的总额(USD)」推出汇率:汇率 = 该窗口的 limitCredits ÷ 总额(credits 每 1 USD)。
         * poolUsd <= 0 或找不到该窗口时返回 null(表示不折算)。
         */
        fun rateOf(windows: List<com.ycm50.subscribemeter.core.QuotaWindow>, poolUsd: Double, poolWindow: String): Double? {
            if (poolUsd <= 0) return null
            val anchor = windows.firstOrNull { it.id == poolWindow } ?: windows.firstOrNull { it.id == "7d" } ?: return null
            val cap = anchor.cap ?: return null
            if (cap <= 0) return null
            return cap / poolUsd
        }

        /**
         * 把所有 credits 窗口按同一汇率折算成 USD 窗口;余额窗口原样保留。
         * 折算后 percent 不变(比例与单位无关),used/cap 换成美元,单位标签变 "USD"。
         */
        fun convertToUsd(
            windows: List<com.ycm50.subscribemeter.core.QuotaWindow>,
            poolUsd: Double,
            poolWindow: String,
        ): List<com.ycm50.subscribemeter.core.QuotaWindow> {
            val rate = rateOf(windows, poolUsd, poolWindow) ?: return windows
            if (rate <= 0) return windows
            return windows.map { window ->
                if (window.unit != "credits" || window.cap == null) {
                    window
                } else {
                    com.ycm50.subscribemeter.core.makeWindow(
                        id = window.id,
                        label = window.label,
                        used = window.used?.div(rate),
                        cap = window.cap / rate,
                        unit = "USD",
                        resetsAt = window.resetsAt,
                        percent = window.percent,
                        note = window.note,
                    ) ?: window
                }
            }
        }

        /**
         * 校验一枚 controlKey 是否真的有效:打一次 workspaces/current。
         * 返回 (workspaceId, tier);无效/过期返回 null。
         * 用途:登录流程里「拿到 key」不等于「可用」,必须验一次再宣布已登录。
         */
        suspend fun verify(http: NetworkClient, controlKey: String): Pair<String, String>? {
            if (controlKey.isBlank()) return null
            val result = runCatching {
                http.get(ORIGIN + "/api/v1/workspaces/current", mapOf("Authorization" to "Bearer " + controlKey.trim()))
            }.getOrNull() ?: return null
            if (!result.ok) return null
            val root = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(result.body) as? kotlinx.serialization.json.JsonObject
            }.getOrNull() ?: return null
            val workspaceId = root["workspaceId"].asTextOrEmpty()
            val tier = root["personalMembershipTier"].asTextOrEmpty()
            if (workspaceId.isEmpty()) return null
            return workspaceId to tier
        }
        /** Design Plan 窗口 credits → USD 的换算比(官方 5 小时 50000 credits = $10)。 */
        const val WINDOW_CREDITS_PER_USD = 5000.0
    }
}