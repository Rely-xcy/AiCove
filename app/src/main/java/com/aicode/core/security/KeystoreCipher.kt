package com.aicode.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感数据的对称加密门面。用于私钥文件与数据库里的密码/API Key 等字段。
 *
 * 密文有两代，读取时都认：
 *
 * - `AICOVEENC2`：自备密钥环（[KeyringStore]）加密，密文里带密钥 id，root 清掉 Keystore 后仍能解。
 * - `AICODEENC1`：旧版 Android Keystore 加密，只在密钥环尚未初始化时用于新数据，以及读取历史密文。
 *
 * 旧密文不做读时惰性升级：解密发生在任意线程、任意时刻，在里面写盘会引入难以排查的并发问题。
 * 历史密文的迁移由轮换流程统一完成，那一步能保证「全部成功才切换」，也能回滚。
 */
object KeystoreCipher {
    private const val TAG = "KeystoreCipher"

    private const val LEGACY_PROVIDER = "AndroidKeyStore"
    private const val LEGACY_ALIAS = "aicode_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 旧一代：字符串前缀与字节文件头。 */
    private const val LEGACY_PREFIX = "AICODEENC1:"
    private val LEGACY_MAGIC = "AICODEENC1\u0000".toByteArray(Charsets.US_ASCII)

    /** 新一代：字符串前缀与字节文件头。keyId 紧跟魔数，用 NUL 分隔。 */
    private const val PREFIX_V2 = "AICOVEENC2:"
    private val MAGIC_V2 = "AICOVEENC2\u0000".toByteArray(Charsets.US_ASCII)
    private const val SEPARATOR = '\u0000'

    private val legacyLock = Any()

    /** 必须在 Application.onCreate 早期调用；未调用时加密退回旧 Keystore 路径，不会崩。 */
    fun init(context: Context) {
        KeyringStore.init(context)
    }

    private fun keyringReady(): Boolean = runCatching {
        KeyringStore.activeKey()
    }.isSuccess

    // ---------- 字节 ----------

    /**
     * 加密：只会走密钥环（AICOVEENC2）。
     *
     * 不再回退旧 Keystore 路径。那个回退会在「密钥环不可用」时写出只能用设备密钥解的新密文，
     * 而设备密钥同样会丢，等于批量制造解不开的数据。写不进去宁可直接报错。
     */
    fun encryptBytes(plain: ByteArray): ByteArray {
        val (entry, key) = KeyringStore.activeKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return MAGIC_V2 + entry.id.toByteArray(Charsets.US_ASCII) +
            byteArrayOf(SEPARATOR.code.toByte()) + iv + ciphertext
    }

    fun isEncryptedBytes(bytes: ByteArray): Boolean =
        hasMagic(bytes, MAGIC_V2) || hasMagic(bytes, LEGACY_MAGIC)

    private fun hasMagic(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }

    fun decryptBytes(encrypted: ByteArray): ByteArray = try {
        decryptBytesOrThrow(encrypted)
    } catch (e: GeneralSecurityException) {
        FileLogger.w(TAG, "解密失败：密钥不匹配或数据损坏", e)
        ByteArray(0)
    }

    private fun decryptBytesOrThrow(encrypted: ByteArray): ByteArray {
        if (hasMagic(encrypted, MAGIC_V2)) {
            val keyIdStart = MAGIC_V2.size
            val sepIndex = encrypted.indexOf(SEPARATOR.code.toByte(), keyIdStart)
            require(sepIndex > keyIdStart) { "密文缺少密钥 id" }
            val keyId = String(encrypted.copyOfRange(keyIdStart, sepIndex), Charsets.US_ASCII)
            val key = KeyringStore.loadKey(keyId)
            if (key == null) {
                FileLogger.e(TAG, "找不到密钥 $keyId，这段密文已成孤儿，不做覆盖")
                throw GeneralSecurityException("密钥 $keyId 不存在")
            }
            val ivStart = sepIndex + 1
            val iv = encrypted.copyOfRange(ivStart, ivStart + IV_LEN)
            val ciphertext = encrypted.copyOfRange(ivStart + IV_LEN, encrypted.size)
            return gcmDecrypt(key, iv, ciphertext)
        }
        if (hasMagic(encrypted, LEGACY_MAGIC)) {
            val iv = encrypted.copyOfRange(LEGACY_MAGIC.size, LEGACY_MAGIC.size + IV_LEN)
            val ciphertext = encrypted.copyOfRange(LEGACY_MAGIC.size + IV_LEN, encrypted.size)
            val key = legacyKeyOrNull()
                ?: throw GeneralSecurityException(
                    "旧格式密文需要设备密钥 $LEGACY_ALIAS，但 Keystore 里已不存在（被清过或换了设备）"
                )
            return gcmDecrypt(key, iv, ciphertext)
        }
        throw GeneralSecurityException("不是加密数据")
    }

    private fun ByteArray.indexOf(target: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == target) return i
        return -1
    }

    private fun gcmDecrypt(key: SecretKey, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    // ---------- 字符串 ----------

    fun isEncryptedString(value: String?): Boolean =
        value != null && (value.startsWith(PREFIX_V2) || value.startsWith(LEGACY_PREFIX))

    /** 加密字符串；空串原样返回，避免把「未填写」变成非空密文。 */
    fun encryptString(plain: String): String {
        if (plain.isEmpty()) return plain
        // 不再回退 AICODEENC1：那个格式的密钥在设备密钥被清后永久不可恢复，
        // 继续写它只会给未来制造更多解不开的数据。密钥环不可用就让它失败。
        val (entry, key) = KeyringStore.activeKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX_V2 + entry.id + ":" + Base64.getEncoder().encodeToString(payload)
    }

    /** 解密字符串；非密文（旧明文）原样返回。 */
    fun decryptString(value: String): String {
        if (!isEncryptedString(value)) return value
        return tryDecryptString(value).getOrElse {
            FileLogger.w(TAG, "解密失败：密文格式非法或密钥缺失", it)
            ""
        }
    }

    /**
     * 可判别的解密：非密文（旧明文）视为成功并返回原值；密文解不开时返回 failure。
     *
     * [decryptString] 解不开时返回空串，与「这个字段本来就是空」无法区分——备份导出若据此写入空串，
     * 用户就永久丢了密钥且备份里没有任何痕迹。需要保留原值或统计「解不开」的调用方改用本方法。
     */
    fun tryDecryptString(value: String): Result<String> {
        if (!isEncryptedString(value)) return Result.success(value)
        return runCatching {
            if (value.startsWith(PREFIX_V2)) {
                val rest = value.removePrefix(PREFIX_V2)
                val sep = rest.indexOf(':')
                require(sep > 0) { "密文缺少密钥 id" }
                val keyId = rest.substring(0, sep)
                val key = KeyringStore.loadKey(keyId)
                    ?: throw GeneralSecurityException("密钥 $keyId 不存在")
                val payload = Base64.getDecoder().decode(rest.substring(sep + 1))
                require(payload.size > IV_LEN) { "密文过短" }
                val iv = payload.copyOfRange(0, IV_LEN)
                val body = payload.copyOfRange(IV_LEN, payload.size)
                String(gcmDecrypt(key, iv, body), Charsets.UTF_8)
            } else {
                val body = Base64.getDecoder().decode(value.removePrefix(LEGACY_PREFIX))
                String(decryptBytesOrThrow(body), Charsets.UTF_8)
            }
        }.onFailure { FileLogger.w(TAG, "解密失败：密文与密钥不匹配或已损坏", it) }
    }

    // ---------- 旧 Keystore 路径 ----------

    /**
     * 只读取旧 Keystore 密钥，**绝不生成**。
     *
     * 旧版 `aicode_secret_v1` 是设备绑定密钥，一旦 Keystore 被清，这把钥匙就永久没了。
     * 之前这里用「取不到就 generateKey()」的写法，于是解密时也会凭空造一把新钥匙，
     * 拿它去解旧密文必然 AEADBadTagException——用户看到的是「密文损坏」，
     * 而真正原因是「密钥丢了」，永远查不出原因。现在只读不建。
     */
    private fun legacyKeyOrNull(): SecretKey? = synchronized(legacyLock) {
        runCatching {
            val keyStore = KeyStore.getInstance(LEGACY_PROVIDER).apply { load(null) }
            (keyStore.getEntry(LEGACY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
    }

    /** 仅为 encryptBytes 使用，decrypt 路径只走 legacyKeyOrNull。 */
    private fun legacyKeyForEncrypt(): SecretKey = synchronized(legacyLock) {
        legacyKeyOrNull() ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, LEGACY_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    LEGACY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }
    }

    private fun legacyEncryptBytes(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, legacyKeyForEncrypt())
        return LEGACY_MAGIC + cipher.iv + cipher.doFinal(plain)
    }
}