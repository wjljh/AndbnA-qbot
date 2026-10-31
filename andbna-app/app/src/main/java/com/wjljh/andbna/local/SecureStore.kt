package com.wjljh.andbna.local

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 落盘加密：Android Keystore 里的 AES-256/GCM 密钥，别名固定，私钥不出 TEE。
 *
 * 存的东西一律带前缀 [PREFIX]，方便和旧明文区分：
 *   enc:v1:<base64(iv(12) + ciphertext + tag(16))>
 * 读的时候没有前缀 = 旧明文 → 原样返回并立刻加密写回（[readPref] / [open] 负责）。
 *
 * 这一层只管「字符串进、字符串出」，业务代码拿到的永远是明文。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val PREFIX = "enc:v1:"
    private const val ALIAS = "andbna_secret_v1"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 这些 SharedPreferences 键是密钥类字段，读写都走本对象 */
    val SECRET_PREF_KEYS = setOf("llm_key", "local_secret", "token")

    fun isEncrypted(v: String?): Boolean = v != null && v.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** 明文 → enc:v1:…；已加密的原样返回；空串保持空串（不写垃圾） */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        if (isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val all = ByteArray(iv.size + body.size)
            System.arraycopy(iv, 0, all, 0, iv.size)
            System.arraycopy(body, 0, all, iv.size, body.size)
            PREFIX + Base64.encodeToString(all, Base64.NO_WRAP)
        } catch (e: Throwable) {
            // 密钥库不可用（个别机型/被清理过）。不假装加密成功：原样返回，下次写入再试。
            Log.e(TAG, "encrypt failed, keep plaintext", e)
            plain
        }
    }

    /** enc:v1:… → 明文；没有前缀 = 旧明文；解不开返回空串（不把密文当密码用） */
    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        return try {
            val all = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (all.size <= IV_LEN) return ""
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_LEN))
            String(cipher.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.e(TAG, "decrypt failed", e)
            ""
        }
    }

    /** 读一个可能加密的键；旧明文顺手加密写回 */
    fun readPref(sp: SharedPreferences, k: String): String {
        val raw = sp.getString(k, "").orEmpty()
        if (raw.isEmpty() || isEncrypted(raw)) return decrypt(raw)
        sp.edit().putString(k, encrypt(raw)).apply()
        return raw
    }

    /** 写一个密钥类键（一律加密） */
    fun writePref(sp: SharedPreferences, k: String, v: String) {
        sp.edit().putString(k, encrypt(v.trim())).apply()
    }

    fun get(sp: SharedPreferences, k: String): String =
        if (k in SECRET_PREF_KEYS) readPref(sp, k) else sp.getString(k, "").orEmpty()

    fun put(sp: SharedPreferences, k: String, v: String) {
        if (k in SECRET_PREF_KEYS) writePref(sp, k, v)
        else sp.edit().putString(k, v.trim()).apply()
    }
}
