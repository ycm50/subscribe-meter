package com.ycm50.subscribemeter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ycm50.subscribemeter.appGraph
import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.data.AppConfig
import com.ycm50.subscribemeter.data.ProviderConfig
import com.ycm50.subscribemeter.providers.OpenDesignDeviceAuth
import com.ycm50.subscribemeter.providers.OpenDesignProvider
import com.ycm50.subscribemeter.repo.RefreshOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 授权登录状态。 */
enum class LoginStatus { IDLE, STARTING, WAITING, SUCCEEDED, FAILED }

data class LoginState(
    val status: LoginStatus = LoginStatus.IDLE,
    val userCode: String = "",
    val activationUrl: String = "",
    val message: String = "",
)

/** 主界面状态。 */
data class UiState(
    val config: AppConfig = AppConfig(),
    val snapshots: Map<String, ProviderSnapshot> = emptyMap(),
    val refreshing: Boolean = false,
    val lastRefreshAt: Long = 0L,
    val message: String = "",
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.appGraph

    private val _state = MutableStateFlow(UiState(config = graph.config.config()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** OpenDesign 浏览器授权登录的界面状态。 */
    private val _login = MutableStateFlow(LoginState())
    val login: StateFlow<LoginState> = _login.asStateFlow()

    private var loginJob: Job? = null
    private var loginDevice: OpenDesignDeviceAuth.DeviceAuthorization? = null

    init {
        // 冷启动:先展示上次快照,再进行首次刷新。
        graph.repository.restoreFromDisk()
        viewModelScope.launch {
            graph.repository.snapshots.collect { snapshots ->
                _state.value = _state.value.copy(snapshots = snapshots)
            }
        }
        viewModelScope.launch {
            graph.repository.refreshing.collect { refreshing ->
                _state.value = _state.value.copy(refreshing = refreshing)
            }
        }
        viewModelScope.launch {
            graph.repository.lastRefreshAt.collect { at ->
                _state.value = _state.value.copy(lastRefreshAt = at)
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val outcome: RefreshOutcome = graph.repository.refreshAll()
            _state.value = _state.value.copy(message = summary(outcome))
            maybeNotify(outcome)
        }
    }

    private fun summary(outcome: RefreshOutcome): String {
        val total = outcome.snapshots.size
        val failed = outcome.snapshots.values.count { it.error.isNotEmpty() }
        return if (failed == 0) "已刷新 " + total + " 个额度源" else "已刷新 " + total + " 个额度源," + failed + " 个失败"
    }

    private fun maybeNotify(outcome: RefreshOutcome) {
        val config = graph.config.config()
        if (!config.notifyEnabled) return
        val tightest = outcome.tightest ?: return
        val percent = tightest.second.percent ?: return
        if (percent < config.notifyThreshold) return
        val provider = config.providers.firstOrNull { it.id == tightest.first.providerId } ?: return
        NotificationHelper.notifyTight(getApplication<Application>(), provider.name, tightest.second.label, percent)
    }

    // ── 配置编辑 ────────────────────────────────────────────────────────────

    fun saveProvider(provider: ProviderConfig, keyValue: String?) {
        graph.config.upsert(provider)
        if (keyValue != null) graph.config.setSecret(provider.id, keyValue)
        _state.value = _state.value.copy(config = graph.config.config())
        refresh()
    }

    fun deleteProvider(providerId: String) {
        graph.config.remove(providerId)
        graph.repository.forget(providerId)
        _state.value = _state.value.copy(config = graph.config.config())
    }

    fun setRefreshMinutes(minutes: Int) {
        val config = graph.config.config()
        graph.config.save(config.copy(refreshMinutes = minutes.coerceIn(1, 1440)))
        _state.value = _state.value.copy(config = graph.config.config())
    }

    fun setNotify(enabled: Boolean, threshold: Int) {
        val config = graph.config.config()
        graph.config.save(config.copy(notifyEnabled = enabled, notifyThreshold = threshold.coerceIn(1, 100)))
        _state.value = _state.value.copy(config = graph.config.config())
    }

    fun setProxy(url: String) {
        val config = graph.config.config()
        graph.config.save(config.copy(proxyUrl = url.trim()))
        _state.value = _state.value.copy(config = graph.config.config())
    }

    fun exportConfig(): String = graph.config.exportJson()

    fun importConfig(text: String): String = runCatching {
        val count = graph.config.importJson(text)
        _state.value = _state.value.copy(config = graph.config.config())
        refresh()
        "已导入 " + count + " 个额度源"
    }.getOrElse { "导入失败:" + (it.message ?: "格式不正确") }

    fun secretOf(providerId: String): String = graph.config.secret(providerId)

    fun hasSecret(providerId: String): Boolean = graph.config.secret(providerId).isNotEmpty()

    fun clearMessage() {
        _state.value = _state.value.copy(message = "")
    }

    // ── OpenDesign 浏览器授权登录 ─────────────────────────────────────────

    /**
     * 发起 Vela 设备授权:拿到验证码与激活链接后开轮询,授权在浏览器完成。
     * 只有 (1) 服务端真的返回了 controlKey、(2) 用该 key 打 workspaces/current 成功,
     * 两个条件都满足才会写入密钥并显示「已登录」。
     */
    fun startOpenDesignLogin(providerId: String) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _login.value = LoginState(status = LoginStatus.STARTING)
            val device = runCatching { OpenDesignDeviceAuth.create(graph.network, "android") }
                .getOrElse { error ->
                    _login.value = LoginState(status = LoginStatus.FAILED, message = "申请授权码失败:" + (error.message ?: "未知错误"))
                    return@launch
                }
            loginDevice = device
            _login.value = LoginState(
                status = LoginStatus.WAITING,
                userCode = device.userCode,
                activationUrl = device.activationUrl,
                message = "在浏览器里确认验证码 " + device.userCode,
            )
            val deadline = System.currentTimeMillis() + 10 * 60 * 1000
            while (System.currentTimeMillis() < deadline) {
                delay(device.pollIntervalSeconds * 1000L)
                when (val polled = OpenDesignDeviceAuth.poll(graph.network, device.deviceId, device.deviceSecret)) {
                    OpenDesignDeviceAuth.PollResult.Pending -> Unit
                    is OpenDesignDeviceAuth.PollResult.Approved -> {
                        // 拿到 key 不等于可用:再打一次 workspaces/current 验证,验过才写盘。
                        val verified = OpenDesignProvider.verify(graph.network, polled.controlKey)
                        if (verified == null) {
                            _login.value = LoginState(
                                status = LoginStatus.FAILED,
                                userCode = device.userCode,
                                activationUrl = device.activationUrl,
                                message = "授权已返回,但登录态校验未通过(未取到工作区),请重试",
                            )
                            return@launch
                        }
                        graph.config.setSecret(providerId, polled.controlKey)
                        if (graph.config.config().providers.any { it.id == providerId && it.workspaceId.isBlank() }) {
                            graph.config.config().providers.firstOrNull { it.id == providerId }?.let { provider ->
                                graph.config.upsert(provider.copy(workspaceId = verified.first))
                            }
                            _state.value = _state.value.copy(config = graph.config.config())
                        }
                        _login.value = LoginState(
                            status = LoginStatus.SUCCEEDED,
                            userCode = device.userCode,
                            activationUrl = device.activationUrl,
                            message = (if (polled.email.isEmpty()) "登录成功" else "登录成功:" + polled.email) +
                                "(工作区 " + verified.first + ")",
                        )
                        _state.value = _state.value.copy(message = "OpenDesign 登录成功")
                        refresh()
                        return@launch
                    }
                    is OpenDesignDeviceAuth.PollResult.Failed -> {
                        _login.value = LoginState(status = LoginStatus.FAILED, message = polled.message)
                        return@launch
                    }
                }
            }
            _login.value = LoginState(status = LoginStatus.FAILED, message = "授权超时,请重新发起登录")
        }
    }

    /** 取消登录(同时通知服务端取消,避免留下悬挂设备)。 */
    fun cancelOpenDesignLogin() {
        val device = loginDevice
        loginJob?.cancel()
        loginJob = null
        loginDevice = null
        _login.value = LoginState()
        if (device != null) {
            viewModelScope.launch { OpenDesignDeviceAuth.cancel(graph.network, device.deviceId, device.deviceSecret) }
        }
    }

}