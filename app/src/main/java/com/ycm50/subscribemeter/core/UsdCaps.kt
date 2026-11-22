package com.ycm50.subscribemeter.core

/**
 * 把「各窗口的美元总额」套到窗口列表上。
 *
 * 规则(四个额度源共用,口径一致):
 *  1) 用户给某窗口填了总额 usdCap:
 *     - 窗口原本没有 used/cap(接口只给百分比,如 OpenCode Go)→ 直接补出 cap = usdCap、used = percent% × usdCap;
 *     - 窗口原本用别的单位(credits / tokens)→ 推出汇率 = 原 cap ÷ usdCap,把**整组**同单位窗口按同一汇率折算,
 *       保证 5 小时 / 周 / 月是同一把尺子;
 *     - 窗口本来就是 USD → 只把 cap 换成用户给的总额,已用按百分比缩放(percent 不变)。
 *  2) 兑钱只认「与美元等价的额度」;钱包余额这类无上限窗口不参与。
 *
 * 一个都没填时原样返回(界面继续显示接口原始单位)。
 */
object UsdCaps {

    /** 这些窗口是无上限的余额,不参与总额折算。 */
    private val UNBOUNDED = setOf("balance")

    /**
     * @param windows 原始窗口
     * @param usdCaps 用户配置:窗口 id → 该窗口的总额(美元)
     */
    fun apply(windows: List<QuotaWindow>, usdCaps: Map<String, Double>): List<QuotaWindow> {
        val caps = usdCaps.filterValues { it > 0 }
        if (caps.isEmpty()) return windows

        // 每种原始单位只认一个汇率锚点:优先用「用户填了总额」的窗口去推。
        val rateByUnit = mutableMapOf<String, Double>()
        for (window in windows) {
            if (window.id in UNBOUNDED) continue
            val usdCap = caps[window.id] ?: continue
            val cap = window.cap ?: continue
            val unit = window.unit
            if (unit == "USD") continue
            if (cap <= 0) continue
            val rate = cap / usdCap
            if (rate > 0) rateByUnit.putIfAbsent(unit, rate)
        }

        return windows.map { window ->
            if (window.id in UNBOUNDED) return@map window
            val usdCap = caps[window.id]
            val rate = rateByUnit[window.unit]

            when {
                // 用户直接给了这个窗口的总额:无论原来什么单位都换算。
                usdCap != null -> {
                    val percent = window.percent ?: 0.0
                    makeWindow(
                        id = window.id,
                        label = window.label,
                        cap = usdCap,
                        used = usdCap * percent / 100.0,
                        unit = "USD",
                        percent = percent,
                        resetsAt = window.resetsAt,
                        note = window.note,
                    ) ?: window
                }

                // 同单位里有人填过总额:跟着同一汇率换算。
                rate != null && window.unit != "USD" && window.cap != null -> makeWindow(
                    id = window.id,
                    label = window.label,
                    used = window.used?.div(rate),
                    cap = window.cap / rate,
                    unit = "USD",
                    percent = window.percent,
                    resetsAt = window.resetsAt,
                    note = window.note,
                ) ?: window

                else -> window
            }
        }
    }
}
