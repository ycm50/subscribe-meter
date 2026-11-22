package com.ycm50.subscribemeter.providers

import com.ycm50.subscribemeter.core.ProviderSnapshot
import com.ycm50.subscribemeter.core.asStrictDouble
import com.ycm50.subscribemeter.data.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一次查询所需的上下文(密钥从 Keystore 保险箱解出,只在这里短暂存在)。 */
data class ProviderContext(
    val config: ProviderConfig,
    val secret: String,
)

/** 所有额度源的统一接口:实现类只负责「把厂商响应翻译成窗口」,不碰 UI/存储。 */
interface QuotaProvider {
    /** 拉取额度;预期内失败请返回带 softMessage 的快照而不是抛异常。 */
    suspend fun fetch(ctx: ProviderContext): ProviderSnapshot
}

/** 给 adapter 复用的结果构造器,保证 error/softMessage/fetchedAt 口径一致。 */
object Snapshot {
    fun ok(
        providerId: String,
        windows: List<com.ycm50.subscribemeter.core.QuotaWindow>,
        subtitle: String = "",
        meta: Map<String, String> = emptyMap(),
        endpoint: String = "",
    ): ProviderSnapshot = ProviderSnapshot(
        providerId = providerId,
        windows = windows,
        subtitle = subtitle,
        meta = meta,
        fetchedAt = System.currentTimeMillis(),
        endpoint = endpoint,
    )

    fun soft(providerId: String, message: String, subtitle: String = ""): ProviderSnapshot = ProviderSnapshot(
        providerId = providerId,
        subtitle = subtitle,
        fetchedAt = System.currentTimeMillis(),
        softMessage = message,
    )

    fun fail(providerId: String, message: String, subtitle: String = "", endpoint: String = ""): ProviderSnapshot = ProviderSnapshot(
        providerId = providerId,
        subtitle = subtitle,
        fetchedAt = System.currentTimeMillis(),
        error = message,
        endpoint = endpoint,
    )
}

/** JSON 取值:按 a.b[0].c 之类的点号路径取元素,取不到返回 null。 */
fun JsonElement.path(dotted: String): JsonElement? {
    if (dotted.isBlank()) return null
    var current: JsonElement = this
    for (segment in dotted.split('.')) {
        if (segment.isEmpty()) continue
        val name = segment.substringBefore('[')
        val indexText = if (segment.contains('[')) segment.substringAfter('[').substringBefore(']') else ""
        if (name.isNotEmpty()) {
            val obj = current as? JsonObject ?: return null
            current = obj[name] ?: return null
        }
        if (indexText.isNotEmpty()) {
            val arr = current as? JsonArray ?: return null
            val index = indexText.toIntOrNull() ?: return null
            current = arr.getOrNull(index) ?: return null
        }
    }
    return current
}

/**
 * 取文本(数字/布尔转文本),取不到返回空串。
 *
 * 注意:JSON null 在 kotlinx 里是 JsonNull(它也是 JsonPrimitive),
 * 直接读 content 会得到字符串 "null" —— 这正是「未授权的 pending 响应被当成
 * 拿到了 controlKey」的成因,所以必须显式把 null 判掉。
 */
fun JsonElement?.asTextOrEmpty(): String {
    if (this == null) return ""
    if (this is kotlinx.serialization.json.JsonNull) return ""
    val prim = this as? JsonPrimitive ?: return ""
    if (prim.content == "null") return ""
    return prim.content
}

/** 取数值(字符串按严格数值解析,空串不当作 0),取不到返回 null。 */
fun JsonElement?.asDoubleOrNull(): Double? {
    val prim = this as? JsonPrimitive ?: return null
    if (prim.isString) return prim.content.asStrictDouble()
    return prim.content.toDoubleOrNull()
}