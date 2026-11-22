package com.ycm50.subscribemeter.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** HTTP 结果(状态码 + 原文);4xx/5xx 不抛异常,交由 adapter 判定语义。 */
data class HttpResult(val status: Int, val body: String, val url: String) {
    val ok: Boolean get() = status in 200..299
    fun json(): String = body
}

/**
 * 极简 HTTP 客户端:只做 GET/POST,支持自定义头、超时与可选 HTTP 代理。
 *
 * 为什么不用 OkHttp:本项目只需要 5 个固定端点,HttpURLConnection 已够用,
 * 少一个依赖就少一份体积与混淆面积。HTTPS 走代理时由 JDK 自身完成 CONNECT 隧道。
 */
class NetworkClient(private val proxyUrlProvider: () -> String) {

    private fun proxyOrNull(): Proxy? {
        val raw = proxyUrlProvider().trim()
        if (raw.isEmpty()) return null
        val withScheme = if (raw.contains("://")) raw else "http://" + raw
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 8080
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 15000,
    ): HttpResult = request("GET", url, headers, null, timeoutMs)

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        bodyText: String? = null,
        timeoutMs: Int = 15000,
    ): HttpResult = request("POST", url, headers, bodyText, timeoutMs)

    private suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        bodyText: String?,
        timeoutMs: Int,
    ): HttpResult = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("非法 URL:" + url) }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "仅支持 http/https:" + url }
        val proxy = proxyOrNull()
        val connection = (if (proxy != null) uri.toURL().openConnection(proxy) else uri.toURL().openConnection()) as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in headers) {
                if (value.isNotEmpty()) connection.setRequestProperty(name, value)
            }
            if (bodyText != null && method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            HttpResult(status = status, body = text, url = url)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    companion object {
        const val USER_AGENT = "subscribe-meter-android/0.1 (Android)"
    }
}
