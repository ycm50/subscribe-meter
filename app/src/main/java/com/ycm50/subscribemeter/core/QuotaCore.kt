/**
 * 额度窗口:所有 provider adapter 的统一产出结构。
 *
 * 关键口径(参考 ycm50/dsh-subscribe-meter 的 windowOfXxx):
 *  - percent 一定有(0-100,已用百分比);
 *  - used/cap 可能缺失(OpenCode Go 只给 percent,Command Code/OpenDesign/网关给 used+cap);
 *  - 「读不到」绝不伪造成 0%:percent 为 null 时该窗口不出现,整个响应按坏响应处理。
 */
package com.ycm50.subscribemeter.core

import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** 单个额度窗口(5 小时 / 周 / 月 / 余额 …)。 */
data class QuotaWindow(
    val id: String,
    val label: String,
    /** 已用百分比 0-100;null 表示该窗口读不到(不会出现在列表里)。 */
    val percent: Double?,
    val used: Double? = null,
    val cap: Double? = null,
    val remaining: Double? = null,
    val unit: String = "",
    /** ISO-8601 重置时刻,读不到为空串。 */
    val resetsAt: String = "",
    val note: String = "",
) {
    val displayPercent: Float
        get() = (percent ?: 0.0).toFloat().coerceIn(0f, 100f)

    /** 有 used/cap 时给具体数值,否则只给百分比。 */
    fun amountText(): String {
        val u = used
        val c = cap
        val unitSuffix = if (unit.isEmpty()) "" else " " + unit
        return when {
            u != null && c != null -> fmt(u) + " / " + fmt(c) + unitSuffix
            remaining != null && unit.isNotEmpty() -> "剩余 " + fmt(remaining) + " " + unit
            else -> String.format("%.1f%%", percent ?: 0.0)
        }
    }

    companion object {
        /** 数值展示:整数不带小数,其余最多两位。 */
        fun fmt(value: Double): String {
            if (!value.isFinite()) return "-"
            val rounded = (value * 100).roundToLong() / 100.0
            return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else String.format("%.2f", rounded)
        }
    }
}

/** Provider 的一次查询结果。 */
data class ProviderSnapshot(
    val providerId: String,
    val windows: List<QuotaWindow> = emptyList(),
    val subtitle: String = "",
    val meta: Map<String, String> = emptyMap(),
    val fetchedAt: Long = 0L,
    /** 软失败(未配置 Key / 无订阅 等预期场景):卡片仍展示,只是带上提示。 */
    val softMessage: String = "",
    /** 硬失败(网络 / 结构变化等)。 */
    val error: String = "",
    val endpoint: String = "",
) {
    val ok: Boolean get() = error.isEmpty() && windows.isNotEmpty()

    val tightest: QuotaWindow?
        get() = windows.maxByOrNull { it.percent ?: 0.0 }
}

/** 严格数值解析:只接受有限数字与纯数值字符串,null/空串/false 一律视为缺失。 */
fun Any?.asStrictDouble(): Double? = when (this) {
    is Number -> toDouble().takeIf { it.isFinite() }
    is String -> {
        val text = trim()
        if (text.isEmpty() || !NUMERIC.matches(text)) null else text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    else -> null
}

private val NUMERIC = Regex("^[+-]?(\\d+(\\.\\d+)?|\\.\\d+)([eE][+-]?\\d+)?$")

/** ISO 字符串 / epoch 秒 / epoch 毫秒 → ISO-8601;读不到返回空串。 */
fun normalizeResetAt(value: Any?): String {
    if (value is String && value.isNotBlank()) {
        val text = value.trim()
        runCatching { Instant.parse(text).toString() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant().toString() }.getOrNull()?.let { return it }
        return ""
    }
    val num = value.asStrictDouble() ?: return ""
    if (num <= 0) return ""
    val millis = if (num > 1e12) num.toLong() else (num * 1000).toLong()
    return runCatching { Instant.ofEpochMilli(millis).toString() }.getOrDefault("")
}

/** 窗口顺序:面板与摘要都按它排,避免同一次刷新里卡片乱跳。 */
val WINDOW_ORDER = listOf("rolling", "5h", "weekly", "7d", "1d", "monthly", "balance", "credits", "tokens")

fun List<QuotaWindow>.sortedByWindowOrder(): List<QuotaWindow> =
    sortedWith(compareBy({ WINDOW_ORDER.indexOf(it.id).let { i -> if (i < 0) 99 else i } }, { it.id }))

/** 归一化入口:三样读数都拿不到时返回 null(绝不把「不可用」当 0%)。 */
fun makeWindow(
    id: String,
    label: String,
    used: Double? = null,
    cap: Double? = null,
    remaining: Double? = null,
    percent: Double? = null,
    unit: String = "",
    resetsAt: Any? = null,
    note: String = "",
): QuotaWindow? {
    val safeUsed = used?.takeIf { it >= 0 }
    val safeCap = cap?.takeIf { it > 0 }
    val safeRemaining = remaining?.takeIf { it >= 0 }
    val pct = when {
        percent != null && percent >= 0 -> percent
        safeUsed != null && safeCap != null -> safeUsed / safeCap * 100.0
        safeRemaining != null && safeCap != null -> (safeCap - safeRemaining) / safeCap * 100.0
        else -> null
    } ?: return null
    val derivedRemaining = safeRemaining ?: safeUsed?.let { u -> safeCap?.let { c -> max(0.0, c - u) } }
    return QuotaWindow(
        id = id,
        label = label,
        percent = max(0.0, min(100.0, pct)),
        used = safeUsed,
        cap = safeCap,
        remaining = derivedRemaining,
        unit = unit,
        resetsAt = normalizeResetAt(resetsAt),
        note = note,
    )
}
