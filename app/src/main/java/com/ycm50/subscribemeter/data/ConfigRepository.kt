package com.ycm50.subscribemeter.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID

/**
 * 配置仓库:config.json(明文、无密钥)+ SecureStore(加密密钥)+ snapshot.json(上次刷新结果)。
 *
 * 设计意图:
 *  - 冷启动先显示上次读数(快照),再后台刷新,避免白屏;
 *  - 任何字段读坏都回退默认值,不因配置损坏而崩溃;
 *  - 密钥根本不在 config.json 里(见 SecretVault),导出配置天然不含密钥。
 */
class ConfigRepository(context: Context) {
    private val configFile = File(context.filesDir, "config.json")
    private val snapshotFile = File(context.filesDir, "snapshot.json")
    val vault = SecretVault(context)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var cached: AppConfig? = null

    @Synchronized
    fun config(): AppConfig {
        cached?.let { return it }
        val loaded = runCatching {
            if (!configFile.exists()) AppConfig() else json.decodeFromString<AppConfig>(configFile.readText())
        }.getOrElse { AppConfig() }
        val sanitized = loaded.copy(
            refreshMinutes = loaded.refreshMinutes.coerceIn(1, 1440),
            notifyThreshold = loaded.notifyThreshold.coerceIn(1, 100),
            providers = loaded.providers.filter { ProviderType.of(it.type) != null },
        )
        cached = sanitized
        return sanitized
    }

    @Synchronized
    fun save(config: AppConfig) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(json.encodeToString(config))
        cached = config
    }

    @Synchronized
    fun upsert(provider: ProviderConfig) {
        val current = config()
        val list = current.providers.toMutableList()
        val index = list.indexOfFirst { it.id == provider.id }
        if (index >= 0) list[index] = provider else list.add(provider)
        save(current.copy(providers = list))
    }

    @Synchronized
    fun remove(providerId: String) {
        val current = config()
        save(current.copy(providers = current.providers.filterNot { it.id == providerId }))
        vault.remove(providerId)
    }

    @Synchronized
    fun setSecret(providerId: String, value: String): Boolean = vault.put(providerId, value)

    fun secret(providerId: String): String = vault.get(providerId)

    fun snapshot(): SnapshotFile = runCatching {
        if (!snapshotFile.exists()) SnapshotFile() else json.decodeFromString<SnapshotFile>(snapshotFile.readText())
    }.getOrElse { SnapshotFile() }

    @Synchronized
    fun saveSnapshot(file: SnapshotFile) {
        runCatching { snapshotFile.writeText(json.encodeToString(file)) }
    }

    /** 导出配置:模型本身不含密钥字段(密钥在 Keystore 里),这里再兜一层剔除。 */
    fun exportJson(): String = json.encodeToString(config())

    /** 导入配置:接受完整 AppConfig,或只含 providers 的片段;返回导入的实例数。 */
    fun importJson(text: String): Int {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "配置内容为空" }
        val imported = runCatching { json.decodeFromString<AppConfig>(trimmed) }
            .recoverCatching {
                val providers = json.decodeFromString<ProviderListOnly>(trimmed).providers
                AppConfig(providers = providers)
            }
            .getOrThrow()
        val valid = imported.providers.filter { ProviderType.of(it.type) != null }
        require(valid.isNotEmpty()) { "配置里没有可识别的额度源" }
        save(imported.copy(providers = valid))
        return valid.size
    }

    /** 新建一个空实例(带随机 id)。 */
    fun newProvider(type: ProviderType): ProviderConfig = when (type) {
        ProviderType.OPENCODE -> ProviderConfig(
            id = newId(),
            type = type.id,
            name = "OpenCode Go",
            openCodeMode = OpenCodeMode.GO.id,
        )

        ProviderType.COMMANDCODE -> ProviderConfig(id = newId(), type = type.id, name = "Command Code GOAT")
        ProviderType.OPENDESIGN -> ProviderConfig(id = newId(), type = type.id, name = "OpenDesign Cloud")
        ProviderType.NEWAPI -> ProviderConfig(id = newId(), type = type.id, name = "new-api")
        ProviderType.CUSTOM -> ProviderConfig(id = newId(), type = type.id, name = "自定义额度")
    }

    private fun newId(): String = "p_" + UUID.randomUUID().toString().replace("-", "").take(10)
}

@kotlinx.serialization.Serializable
data class ProviderListOnly(val providers: List<ProviderConfig> = emptyList())