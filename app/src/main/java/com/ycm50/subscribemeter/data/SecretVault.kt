package com.ycm50.subscribemeter.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥保险箱:用 Android Keystore 里的 AES-256-GCM 密钥加密后写入应用私有文件。
 *
 * - 密钥材料永不离开 Keystore(不可导出),文件被拖走也解不开;
 * - 每个条目独立 IV,GCM 自带完整性校验;
 * - 解密失败(换机/清除凭据)时安全地当作「未配置」,不会崩溃。
 */
class SecretVault(context: Context) {
    private val file = File(context.filesDir, "secrets.bin")
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val cache = mutableMapOf<String, String>()
    private var loaded = false

    /** 读取明文密钥;没有或解不开返回空串。 */
    @Synchronized
    fun get(providerId: String): String {
        ensureLoaded()
        return cache[providerId] ?: ""
    }

    @Synchronized
    fun has(providerId: String): Boolean = get(providerId).isNotEmpty()

    /** 写入(空串 = 删除)。返回是否发生了写入。 */
    @Synchronized
    fun put(providerId: String, value: String): Boolean {
        ensureLoaded()
        val text = value.trim()
        if (text.isEmpty()) {
            if (cache.remove(providerId) != null) {
                persist()
                return true
            }
            return false
        }
        if (cache[providerId] == text) return false
        cache[providerId] = text
        persist()
        return true
    }

    @Synchronized
    fun remove(providerId: String) {
        put(providerId, "")
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val container = runCatching { Json.decodeFromString<SecretFile>(text) }.getOrNull() ?: return
        for ((id, entry) in container.entries) {
            val plain = decrypt(entry) ?: continue
            cache[id] = plain
        }
    }

    private fun persist() {
        val entries = cache.mapValues { (_, plain) -> encrypt(plain) }
        val text = Json.encodeToString(SecretFile(entries))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): SecretEntry {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return SecretEntry(
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            data = Base64.encodeToString(cipherText, Base64.NO_WRAP),
        )
    }

    private fun decrypt(entry: SecretEntry): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(entry.iv, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(Base64.decode(entry.data, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "subscribe_meter_vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

@kotlinx.serialization.Serializable
data class SecretEntry(val iv: String, val data: String)

@kotlinx.serialization.Serializable
data class SecretFile(val entries: Map<String, SecretEntry> = emptyMap())
