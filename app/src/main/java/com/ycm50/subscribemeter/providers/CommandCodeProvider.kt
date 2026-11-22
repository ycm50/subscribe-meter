package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.net.NetworkClient
import kotlin.math.max

/**
 * Command Code 订阅额度(GOAT 等套餐)。
 *
 * GET https://api.commandcode.ai/alpha/billing/credits(Bearer = COMMANDCODE_API_KEY)
 *   { credits: { monthlyCredits, ... },
 *     windowLimits: { fiveHour: { used, cap, resetAt }, weekly: { used, cap, resetAt } } }
 *
 * 口径要点(与 ycm50/dsh-subscribe-meter 一致):
 *  - fiveHour / weekly 的 used、cap 由接口直接给出,原样采信;
 *  - credits.monthlyCredits 是「当前计费模型那一档」的**剩余额**,不是全局池;
 *    因此月度池必须可配置(默认按 GOAT $70),否则用 $70 去减 $40 档会算出负数/虚高已用。
 */
class CommandCodeProvider(private val http: NetworkClient) : QuotaProvider {

    override suspend fun fetch(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        val plan = Plans.of(cfg.plan)
        val subtitle = plan.label + "(\$" + trimNumber(plan.price) + "/月)"
        if (ctx.secret.isEmpty()) {
            return Snapshot.soft(id, "未配置 Command Code API Key:填入 COMMANDCODE_API_KEY(设置 → 该额度源 → 密钥)", subtitle)
        }
        val url = "https://api.commandcode.ai/alpha/billing/credits"
        val result = runCatching { http.get(url, mapOf("Authorization" to "Bearer " + ctx.secret)) }
            .getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), subtitle, url) }
        if (result.status == 401 || result.status == 403) {
            return Snapshot.soft(id, "没有检测到生效的 Command Code 订阅(HTTP " + result.status + "),或 API Key 无效", subtitle)
        }
        if (!result.ok) return Snapshot.fail(id, "额度接口返回 HTTP " + result.status, subtitle, url)

        val pool = if (cfg.monthlyPool > 0) cfg.monthlyPool else plan.monthlyCredits
        val windows = com.ycm50.subscribemeter.core.UsdRatio.apply(
            Parsers.commandCode(result.body, pool),
            cfg.windowUsdCaps,
            listOf("5h", "weekly", "monthly"),
        )
        if (windows.isEmpty()) {
            return Snapshot.fail(id, "响应里既没有 windowLimits 也没有可用的 monthlyCredits,接口结构可能已变化", subtitle, url)
        }
        val meta = buildMap<String, String> {
            put("月度池", trimNumber(pool) + " USD")
            if (cfg.monthlyPool <= 0) put("月度池来源", "套餐默认" + plan.label)
        }
        return Snapshot.ok(id, windows, subtitle, meta, url)
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
}

/** 官方套餐表(2026-09 抓取复核:plans/goat、resources/pricing-limits、resources/usage-limits)。 */
enum class Plans(val id: String, val label: String, val price: Double, val monthlyCredits: Double, val fiveHourCap: Double, val weeklyCap: Double) {
    GO("go", "Go", 1.0, 10.0, 3.0, 6.0),
    GOAT("goat", "GOAT", 10.0, 70.0, 14.0, 35.0),
    PRO("pro", "Pro", 20.0, 80.0, 16.0, 40.0),
    MAX10("max10x", "Max 10×", 100.0, 150.0, 45.0, 90.0),
    MAX20("max20x", "Max 20×", 200.0, 300.0, 90.0, 180.0),
    TEAM_PRO("teampro", "Team Pro", 40.0, 40.0, 12.0, 24.0),
    ;

    companion object {
        fun of(id: String?): Plans = entries.firstOrNull { it.id == id } ?: GOAT
    }
}