package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.QuotaWindow
import com.ycm50.subscribemeter.core.sortedByWindowOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 纯解析层:厂商响应模板 → 额度窗口。网络与存储不在这里,便于在 JVM 上直接单测。 */
object Parsers {

    /** OpenCode Go:usage.{rolling,weekly,monthly}.{percent,resetsAt,status}。 */
    fun openCodeGo(json: String): List<QuotaWindow> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val usage = root["usage"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<QuotaWindow>()
        for ((key, label) in linkedMapOf("rolling" to "5 小时", "weekly" to "本周", "monthly" to "本月")) {
            val node = usage[key] as? JsonObject ?: continue
            val percent = node["percent"].asDoubleOrNull() ?: continue
            val limited = node["status"].asTextOrEmpty().equals("rate-limited", ignoreCase = true)
            com.ycm50.subscribemeter.core.makeWindow(
                id = key,
                label = label,
                percent = percent,
                resetsAt = node["resetsAt"],
                note = if (limited) "限流中(正常业务态)" else "",
            )?.let { out.add(it) }
        }
        return out.sortedByWindowOrder()
    }

    /** Command Code:windowLimits.fiveHour/weekly + credits.monthlyCredits(剩余额)与可配置月度池。 */
    fun commandCode(json: String, monthlyPool: Double): List<QuotaWindow> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuotaWindow>()
        val limits = root["windowLimits"] as? JsonObject
        for ((key, slot, label) in listOf(
            Triple("fiveHour", "5h", "5 小时"),
            Triple("weekly", "weekly", "本周"),
        )) {
            val node = limits?.get(key) as? JsonObject ?: continue
            com.ycm50.subscribemeter.core.makeWindow(
                id = slot,
                label = label,
                used = node["used"].asDoubleOrNull(),
                cap = node["cap"].asDoubleOrNull(),
                unit = "USD",
                resetsAt = node["resetAt"],
            )?.let { out.add(it) }
        }
        val remaining = ((root["credits"] as? JsonObject)?.get("monthlyCredits")).asDoubleOrNull()
        if (remaining != null && monthlyPool > 0) {
            val safeRemaining = kotlin.math.max(0.0, remaining)
            val used = kotlin.math.max(0.0, monthlyPool - safeRemaining)
            com.ycm50.subscribemeter.core.makeWindow(
                id = "monthly",
                label = "月度池",
                used = used,
                cap = monthlyPool,
                unit = "USD",
                note = if (remaining > monthlyPool) "剩余额大于所选池,请调大月度额度池" else "",
            )?.let { out.add(it) }
        }
        return out.sortedByWindowOrder()
    }

    /** OpenDesign:balanceUsd + codingPlan.windows[](durationSeconds → 5h/7d)。 */
    fun openDesign(json: String): List<QuotaWindow> {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<QuotaWindow>()
        val plan = root["codingPlan"] as? JsonObject
        val raw = (plan?.get("windows") as? JsonArray) ?: JsonArray(emptyList())
        for (element in raw) {
            val node = element as? JsonObject ?: continue
            val seconds = node["durationSeconds"].asDoubleOrNull() ?: continue
            val used = node["usedCredits"].asDoubleOrNull() ?: continue
            val cap = node["limitCredits"].asDoubleOrNull() ?: continue
            if (cap <= 0) continue
            val hours = Math.round(seconds / 3600.0)
            val slot = when (seconds.toLong()) {
                18000L -> "5h" to "5 小时"
                604800L -> "7d" to "7 天"
                else -> hours.toString() + "h" to hours.toString() + " 小时"
            }
            com.ycm50.subscribemeter.core.makeWindow(
                id = slot.first,
                label = slot.second,
                used = used,
                cap = cap,
                unit = "credits",
                resetsAt = node["resetsAt"],
            )?.let { out.add(it) }
        }
        root["balanceUsd"].asDoubleOrNull()?.let { balance ->
            com.ycm50.subscribemeter.core.makeWindow(
                id = "balance",
                label = "钱包余额",
                remaining = balance,
                percent = 0.0,
                unit = "USD",
                note = "预付费余额(无上限)",
            )?.let { out.add(it) }
        }
        return out.sortedByWindowOrder()
    }

    /** new-api 计费端点:{ hard_limit_usd } + { total_usage(美分) }。 */
    fun newApiBilling(subscriptionJson: String, usageJson: String, unit: String = "USD"): QuotaWindow? {
        val sub = unwrap(subscriptionJson) ?: return null
        if (sub["error"] is JsonObject) return null
        val cap = sub["hard_limit_usd"].asDoubleOrNull() ?: sub["soft_limit_usd"].asDoubleOrNull() ?: return null
        val usage = unwrap(usageJson) ?: return null
        if (usage["error"] is JsonObject) return null
        val cents = usage["total_usage"].asDoubleOrNull() ?: return null
        if (cents < 0) return null
        return com.ycm50.subscribemeter.core.makeWindow("monthly", "额度", used = cents / 100.0, cap = cap, unit = unit)
    }

    /** new-api 用户信息:{ quota(剩余), used_quota(已用) }。 */
    fun newApiSelf(json: String, unit: String = "USD"): QuotaWindow? {
        val root = unwrap(json) ?: return null
        val remaining = root["quota"].asDoubleOrNull() ?: return null
        val used = root["used_quota"].asDoubleOrNull() ?: return null
        if (remaining < 0 || used < 0) return null
        return com.ycm50.subscribemeter.core.makeWindow("monthly", "额度", used = used, cap = remaining + used, unit = unit)
    }

    /** 兼容裸对象与 { success, data } 信封。 */
    fun unwrap(json: String): JsonObject? {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        return (root["data"] as? JsonObject) ?: root
    }
}
