package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.net.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * OpenDesign(Vela)设备码授权登录:与官方 vela login 同一条协议。
 *
 * 实测协议(2026-11-22 对着 amr-api.open-design.ai 核对):
 *  1) POST /api/v1/cli/device-authorizations
 *       { capabilities: { completionReceipt: true }, cliVersion, host, profile }
 *     -> 201 { deviceId, userCode, deviceSecret, activationUrl, expiresAt, pollIntervalSeconds }
 *  2) 用户在浏览器打开 activationUrl,页面里确认 userCode
 *  3) POST /api/v1/cli/device-authorizations/{deviceId}/token
 *       { claimRegistrationInvite: true, deviceSecret }
 *     -> 200 { status: "pending", controlKey: null } 继续等
 *        { status: "approved", controlKey: "vela_ctrl_...", user: { email } } 完成
 *  4) 取消:POST /api/v1/cli/device-authorizations/{deviceId}/cancel { deviceSecret }
 *
 * 关键:未授权时 controlKey 是 JSON null。必须显式判 null,否则会把字符串 "null"
 * 当成已登录 —— 这正是上一版误报「已登录」的根因。
 */
object OpenDesignDeviceAuth {

    const val ORIGIN = "https://amr-api.open-design.ai"
    private const val CLI_VERSION = "0.1.10"

    data class DeviceAuthorization(
        val deviceId: String,
        val userCode: String,
        val activationUrl: String,
        val deviceSecret: String,
        val expiresAt: String,
        val pollIntervalSeconds: Int,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data class Approved(val controlKey: String, val email: String) : PollResult
        data class Failed(val message: String) : PollResult
    }

    /** 第一步:申请设备码。 */
    suspend fun create(http: NetworkClient, hostLabel: String): DeviceAuthorization {
        val body = buildJsonObject {
            putJsonObject("capabilities") { put("completionReceipt", true) }
            put("cliVersion", CLI_VERSION)
            put("host", hostLabel)
            put("profile", "prod")
        }.toString()
        val result = http.post(
            ORIGIN + "/api/v1/cli/device-authorizations",
            mapOf("Content-Type" to "application/json"),
            body,
        )
        if (!result.ok) throw IllegalStateException("申请授权码失败(HTTP " + result.status + ")")
        val root = runCatching { Json.parseToJsonElement(result.body) as? JsonObject }.getOrNull()
            ?: throw IllegalStateException("授权响应不是合法 JSON")
        val deviceId = root["deviceId"].asTextOrEmpty()
        val userCode = root["userCode"].asTextOrEmpty()
        val deviceSecret = root["deviceSecret"].asTextOrEmpty()
        if (deviceId.isEmpty() || userCode.isEmpty() || deviceSecret.isEmpty()) {
            throw IllegalStateException("授权响应缺少必需字段")
        }
        val interval = root["pollIntervalSeconds"].asDoubleOrNull()?.toInt() ?: 2
        return DeviceAuthorization(
            deviceId = deviceId,
            userCode = userCode,
            activationUrl = root["activationUrl"].asTextOrEmpty()
                .ifEmpty { "https://open-design.ai/cloud/cli/activate?deviceId=" + deviceId + "&userCode=" + userCode },
            deviceSecret = deviceSecret,
            expiresAt = root["expiresAt"].asTextOrEmpty(),
            pollIntervalSeconds = interval.coerceIn(1, 30),
        )
    }

    /** 第二步:轮询换 controlKey。 */
    suspend fun poll(http: NetworkClient, deviceId: String, deviceSecret: String): PollResult {
        val body = buildJsonObject {
            put("claimRegistrationInvite", true)
            put("deviceSecret", deviceSecret)
        }.toString()
        val result = http.post(
            ORIGIN + "/api/v1/cli/device-authorizations/" + deviceId + "/token",
            mapOf("Content-Type" to "application/json"),
            body,
        )
        if (result.status == 410 || result.status == 404) return PollResult.Failed("授权已失效,请重新发起登录")
        if (!result.ok) return PollResult.Failed("查询授权状态失败(HTTP " + result.status + ")")
        val root = runCatching { Json.parseToJsonElement(result.body) as? JsonObject }.getOrNull()
            ?: return PollResult.Failed("授权响应不是合法 JSON")
        val status = root["status"].asTextOrEmpty().lowercase()
        // 只有真的拿到非空 controlKey 才算授权通过;JSON null 一律按 pending 处理。
        val controlKey = root["controlKey"].asTextOrEmpty()
        if (controlKey.isNotEmpty()) {
            val email = ((root["user"] as? JsonObject)?.get("email"))?.asTextOrEmpty().orEmpty()
            return PollResult.Approved(controlKey, email)
        }
        return when (status) {
            "denied", "expired", "cancelled", "canceled" -> PollResult.Failed("授权被取消或已过期")
            else -> PollResult.Pending
        }
    }

    /** 取消本次授权(关闭弹窗时调用,避免服务端留下悬挂设备)。 */
    suspend fun cancel(http: NetworkClient, deviceId: String, deviceSecret: String) {
        val body = buildJsonObject { put("deviceSecret", deviceSecret) }.toString()
        runCatching {
            http.post(
                ORIGIN + "/api/v1/cli/device-authorizations/" + deviceId + "/cancel",
                mapOf("Content-Type" to "application/json"),
                body,
            )
        }
    }
}
