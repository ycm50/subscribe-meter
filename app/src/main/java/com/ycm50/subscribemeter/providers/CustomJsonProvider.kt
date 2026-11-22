package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.makeWindow
import com.ycm50.subscribemeter.net.NetworkClient
import kotlinx.serialization.json.Json

/**
 * 自定义 JSON 额度源:给「还没内置 adapter」的站点留的后门。
 *
 * 配置:URL、方法、认证头名与前缀、请求体,以及四个点号路径:
 *   percent 路径(例如 data.used_percent)— 优先;used / cap 路径 — percent 缺失时用它算;
 *   reset 路径 — 重置时刻(ISO / epoch 秒 / 毫秒)。
 * 路径用 . 分隔,数组下标写 [n],例如 data.items[0].percent。
 */
class CustomJsonProvider(private val http: NetworkClient) : QuotaProvider {

    override suspend fun fetch(ctx: ProviderContext): ProviderSnapshot {
        val cfg = ctx.config
        val id = cfg.id
        val url = cfg.customUrl.trim()
        if (url.isEmpty()) return Snapshot.fail(id, "未填写额度接口地址", "自定义")
        val headers = buildMap {
            if (cfg.customHeader.isNotBlank()) put(cfg.customHeader.trim(), cfg.customPrefix + ctx.secret)
        }
        val method = cfg.customMethod.trim().uppercase().ifEmpty { "GET" }
        val result = runCatching {
            if (method == "POST") http.post(url, headers, cfg.customBody.takeIf { it.isNotBlank() })
            else http.get(url, headers)
        }.getOrElse { return Snapshot.fail(id, "网络请求失败:" + (it.message ?: "未知错误"), "自定义", url) }
        if (result.status == 401 || result.status == 403) {
            return Snapshot.soft(id, "鉴权失败(HTTP " + result.status + "),检查密钥与认证头", "自定义")
        }
        if (!result.ok) return Snapshot.fail(id, "接口返回 HTTP " + result.status, "自定义", url)
        val root = runCatching { Json.parseToJsonElement(result.body) }.getOrNull()
            ?: return Snapshot.fail(id, "响应不是合法 JSON", "自定义", url)

        val percent = root.path(cfg.customJsonPath).asDoubleOrNull()
        val used = root.path(cfg.customUsedPath).asDoubleOrNull()
        val cap = root.path(cfg.customCapPath).asDoubleOrNull()
        val reset = root.path(cfg.customResetPath)

        // percent 给的是 0-1 小数时自动放大成 0-100(各家约定不一致,这里只对「显式配了 percent 路径」生效)。
        val normalizedPercent = when {
            percent == null -> null
            percent in 0.0..1.0 && cfg.customJsonPath.isNotBlank() -> percent * 100.0
            else -> percent
        }

        val window = makeWindow(
            id = "monthly",
            label = "额度",
            used = used,
            cap = cap,
            percent = normalizedPercent,
            unit = cfg.customUnit.trim(),
            resetsAt = reset,
        ) ?: return Snapshot.fail(id, "按配置的路径没有读到有效数值,请检查 JSON 路径", "自定义", url)

        val windows = com.ycm50.subscribemeter.core.UsdCaps.apply(listOf(window), cfg.windowUsdCaps)
        return Snapshot.ok(id, windows, "自定义 · " + url.substringAfter("://").take(28), emptyMap(), url)
    }
}