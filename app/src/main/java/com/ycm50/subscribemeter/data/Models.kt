package com.ycm50.subscribemeter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 支持的额度源类型。 */
enum class ProviderType(val id: String, val title: String, val hint: String) {
    OPENCODE("opencode", "OpenCode Zen / Go", "opencode.ai 订阅额度(Go 三档窗口 / Zen 预付费余额)"),
    COMMANDCODE("commandcode", "Command Code GOAT", "api.commandcode.ai 订阅额度(5h / 周 / 月度池)"),
    OPENDESIGN("opendesign", "OpenDesign Cloud", "amr-api.open-design.ai(Vela 登录态 controlKey)"),
    NEWAPI("newapi", "new-api / one-api", "自建或中转网关(/api/user/self、/v1/dashboard/billing/*)"),
    CUSTOM("custom", "自定义 JSON", "任意返回 JSON 的额度接口,用 JSONPath 取字段"),
    ;

    companion object {
        fun of(id: String?): ProviderType? = entries.firstOrNull { it.id == id }
    }
}

/** OpenCode 的两种额度口径。 */
enum class OpenCodeMode(val id: String, val title: String) {
    GO("go", "Go 订阅(三档窗口)"),
    ZEN("zen", "Zen 预付费余额(控制台 Cookie)"),
    ;

    companion object {
        fun of(id: String?): OpenCodeMode = entries.firstOrNull { it.id == id } ?: GO
    }
}

/** 一个额度源实例(同类型可建多个账号)。 */
@Serializable
data class ProviderConfig(
    val id: String,
    val type: String,
    val name: String,
    val enabled: Boolean = true,
    // OpenCode
    val openCodeMode: String = "go",
    val openCodeBaseUrl: String = "https://opencode.ai",
    /** 月度额度池(美元等值 credits);0 = 用套餐默认值。 */
    val monthlyPool: Double = 0.0,
    /** Zen 控制台 Cookie(__Host-console_session=...)。 */
    val cookie: String = "",
    /** Zen 控制台工作区 id(x-org-id);留空时自动拉 /console/api/orgs 取第一个。 */
    val orgId: String = "",
    // Command Code
    val plan: String = "goat",
    // OpenDesign
    val workspaceId: String = "",
    /** 总额(美元):填了就把 credits 按同汇率折算成 USD 显示;0 = 原样显示 credits。 */
    val openDesignUsdPool: Double = 0.0,
    /** 上面这个「总额」对应哪个窗口:「7d」或「5h」;汇率由该窗口的 limitCredits ÷ 总额 推出。 */
    val openDesignUsdPoolWindow: String = "7d",
    /** 手动指定汇率(credits 每 1 USD);0 = 由总额自动推算。 */
    val openDesignCreditsPerUsd: Double = 0.0,
    // new-api / one-api
    val baseUrl: String = "",
    val gatewayKind: String = "auto",
    /**
     * 各窗口的「额度总额(美元)」,key = 窗口 id(rolling / 5h / weekly / 7d / monthly …)。
     * 填了的窗口会按美元展示(例如只给百分比的接口凭空补出 used/cap);
     * 原始单位是 credits/tokens 的窗口会按「该窗口 cap ÷ 总额」推出汇率后同汇率折算。
     */
    val windowUsdCaps: Map<String, Double> = emptyMap(),

    // custom
    val customUrl: String = "",
    val customMethod: String = "GET",
    val customHeader: String = "Authorization",
    val customPrefix: String = "Bearer ",
    val customBody: String = "",
    val customJsonPath: String = "",
    val customUsedPath: String = "",
    val customCapPath: String = "",
    val customResetPath: String = "",
    val customUnit: String = "USD",
)

/** 应用配置(不含任何密钥)。 */
@Serializable
data class AppConfig(
    val version: Int = 1,
    val refreshMinutes: Int = 15,
    val proxyUrl: String = "",
    val notifyEnabled: Boolean = true,
    /** 通知阈值:最紧张窗口超过该百分比时提醒。 */
    val notifyThreshold: Int = 85,
    val providers: List<ProviderConfig> = emptyList(),
)

/** 刷新结果(可持久化,作为冷启动首屏)。 */
@Serializable
data class WindowRecord(
    val id: String,
    val label: String,
    val percent: Double?,
    val used: Double? = null,
    val cap: Double? = null,
    val remaining: Double? = null,
    val unit: String = "",
    val resetsAt: String = "",
    val note: String = "",
)

@Serializable
data class SnapshotRecord(
    val providerId: String,
    val windows: List<WindowRecord> = emptyList(),
    val subtitle: String = "",
    val meta: Map<String, String> = emptyMap(),
    val fetchedAt: Long = 0L,
    val softMessage: String = "",
    val error: String = "",
    val endpoint: String = "",
)

@Serializable
data class SnapshotFile(val savedAt: Long = 0L, val snapshots: List<SnapshotRecord> = emptyList())

/** 命令行风格的 key 脱敏:只保留头尾,便于用户确认「存的是哪把钥匙」。 */
fun maskSecret(value: String): String {
    val text = value.trim()
    return when {
        text.isEmpty() -> ""
        text.length <= 8 -> "****"
        else -> text.substring(0, 4) + "****" + text.substring(text.length - 4)
    }
}