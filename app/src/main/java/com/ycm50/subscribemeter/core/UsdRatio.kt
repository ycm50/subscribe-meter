package com.ycm50.subscribemeter.core

/**
 * 三档额度源(OpenCode / Command Code / OpenDesign):**填任意一档的美元总额,
 * 其余档按接口返回的数值比例自动推导**。
 *
 * 推导依据是「接口自己给的数」,不是拍脑袋的常数:
 *  - 非美元单位(OpenDesign 的 credits、网关的 tokens 等):
 *      汇率 = 锚点档 cap ÷ 用户填的总额,其余同单位档按各自 cap 折算。
 *      例:7 天 150000 credits 填 $30 → 5000 credits/$ → 5 小时 50000 credits 自动 = $10。
 *  - 接口本来就分档给了美元金额:各档各自为准,只把锚点档的分母换成用户填的值。
 *  - 接口只给百分比(OpenCode Go):拿不到额度数值,只能按「同一总额」推导,
 *    并在窗口上标注口径,避免让用户以为比例是接口给的。
 *
 * 换算后 percent 保持接口原值(比例与单位无关),只把 used / cap 换成美元。
 */
object UsdRatio {

    /** 无上限的余额窗口,不参与推导。 */
    private val UNBOUNDED = setOf("balance")

    /**
     * @param windows 原始窗口
     * @param usdCaps 用户填的「窗口 id → 该档总额(美元)」
     * @param slots 该额度源的三档窗口 id(按档位从短到长)
     */
    fun apply(windows: List<QuotaWindow>, usdCaps: Map<String, Double>, slots: List<String>): List<QuotaWindow> {
        val filled = usdCaps.filterKeys { it in slots }.filterValues { it > 0 }
        if (filled.isEmpty()) return windows

        val anchorId = filled.keys.first()
        val anchorUsd = filled.getValue(anchorId)
        val anchor = windows.firstOrNull { it.id == anchorId }

        // A. 锚点有具体额度数值且不是美元 → 按 cap 比例推导所有同单位窗口。
        val anchorCap = anchor?.cap
        val anchorUnit = anchor?.unit ?: ""
        if (anchorCap != null && anchorCap > 0 && anchorUnit.isNotEmpty() && anchorUnit != "USD") {
            val rate = anchorCap / anchorUsd // 1 USD 折合多少原始单位
            if (rate > 0) {
                return windows.map { window ->
                    if (window.id in UNBOUNDED) return@map window
                    if (window.unit != anchorUnit || window.cap == null) return@map window
                    val percent = window.percent ?: return@map window
                    makeWindow(
                        id = window.id,
                        label = window.label,
                        cap = window.cap / rate,
                        used = window.cap / rate * percent / 100.0,
                        unit = "USD",
                        percent = percent,
                        resetsAt = window.resetsAt,
                        note = window.note.ifEmpty { "按 " + anchorId + " 的比例推导" },
                    ) ?: window
                }
            }
        }

        // B. 锚点本身是美元 → 只替换锚点这一档的分母(其余档接口各有金额,以接口为准)。
        if (anchorCap != null && anchorCap > 0 && anchorUnit == "USD") {
            return windows.map { window ->
                if (window.id != anchorId) return@map window
                val percent = window.percent ?: return@map window
                makeWindow(
                    id = window.id,
                    label = window.label,
                    cap = anchorUsd,
                    used = anchorUsd * percent / 100.0,
                    unit = "USD",
                    percent = percent,
                    resetsAt = window.resetsAt,
                    note = window.note,
                ) ?: window
            }
        }

        // C. 接口只给百分比(OpenCode Go):拿不到额度数值,按同一总额推导并标注。
        return windows.map { window ->
            if (window.id in UNBOUNDED) return@map window
            val percent = window.percent ?: return@map window
            makeWindow(
                id = window.id,
                label = window.label,
                cap = anchorUsd,
                used = anchorUsd * percent / 100.0,
                unit = "USD",
                percent = percent,
                resetsAt = window.resetsAt,
                note = window.note.ifEmpty { "接口只给百分比,三档按同额 " + fmt(anchorUsd) + " USD 折算" },
            ) ?: window
        }
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else String.format("%.2f", value)
}
