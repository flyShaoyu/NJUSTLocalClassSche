package com.classsche.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the portal password encrypted with a non-exportable Android Keystore key.
 *
 * The key deliberately does not require biometric confirmation because scheduled
 * score sync must be able to decrypt the password while the app is in background.
 */
internal object CredentialStore {
  private const val PREFS_NAME = "classsche_prefs"
  private const val USERNAME_KEY = "username"
  private const val LEGACY_PASSWORD_KEY = "password"
  private const val ENCRYPTED_PASSWORD_KEY = "password_encrypted_v1"
  private const val KEY_ALIAS = "classsche_portal_password_v1"
  private const val PAYLOAD_PREFIX = "v1"
  private const val ANDROID_KEY_STORE = "AndroidKeyStore"
  private const val TRANSFORMATION = "AES/GCM/NoPadding"
  private val associatedData = "classsche-password-v1".toByteArray(Charsets.UTF_8)

  data class Credentials(val username: String, val password: String)

  fun username(context: Context): String = preferences(context)
    .getString(USERNAME_KEY, "")
    .orEmpty()
    .trim()

  @Synchronized
  fun password(context: Context): String? {
    val prefs = preferences(context)
    val encrypted = prefs.getString(ENCRYPTED_PASSWORD_KEY, null)
    if (!encrypted.isNullOrBlank()) {
      return runCatching { decrypt(encrypted) }.getOrNull()
    }

    // One-time migration for installations that previously stored plaintext.
    val legacy = prefs.getString(LEGACY_PASSWORD_KEY, null)?.takeIf { it.isNotBlank() }
      ?: return null
    return try {
      val payload = encrypt(legacy)
      if (!prefs.edit()
          .putString(ENCRYPTED_PASSWORD_KEY, payload)
          .remove(LEGACY_PASSWORD_KEY)
          .commit()) {
        error("无法保存迁移后的加密密码")
      }
      legacy
    } catch (_: Exception) {
      // Never keep returning a plaintext password when secure migration fails.
      // The user can enter it again after the Keystore becomes available.
      prefs.edit().remove(LEGACY_PASSWORD_KEY).commit()
      null
    }
  }

  fun credentials(context: Context): Credentials? {
    val username = username(context)
    val password = password(context).orEmpty().trim()
    return if (username.isBlank() || password.isBlank()) null else Credentials(username, password)
  }

  @Synchronized
  fun save(context: Context, username: String, password: String): Boolean {
    val prefs = preferences(context)
    return try {
      require(username.isNotBlank() && password.isNotBlank())
      val payload = encrypt(password)
      val saved = prefs.edit()
        .putString(USERNAME_KEY, username.trim())
        .putString(ENCRYPTED_PASSWORD_KEY, payload)
        .remove(LEGACY_PASSWORD_KEY)
        .commit()
      if (!saved) prefs.edit().remove(LEGACY_PASSWORD_KEY).commit()
      saved
    } catch (_: Exception) {
      prefs.edit().remove(LEGACY_PASSWORD_KEY).commit()
      false
    }
  }

  private fun preferences(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private fun encrypt(plainText: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    cipher.updateAAD(associatedData)
    val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
    val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    val ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP)
    return "$PAYLOAD_PREFIX:$iv:$ciphertext"
  }

  private fun decrypt(payload: String): String {
    val parts = payload.split(':', limit = 3)
    require(parts.size == 3 && parts[0] == PAYLOAD_PREFIX) { "不支持的密码存储格式" }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    val iv = Base64.decode(parts[1], Base64.NO_WRAP)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
    cipher.updateAAD(associatedData)
    val decrypted = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
    return String(decrypted, Charsets.UTF_8)
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    runCatching { keyStore.getKey(KEY_ALIAS, null) as? SecretKey }
      .getOrNull()
      ?.let { return it }
    if (keyStore.containsAlias(KEY_ALIAS)) {
      keyStore.deleteEntry(KEY_ALIAS)
    }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
    keyGenerator.init(
      KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
      )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .build()
    )
    return keyGenerator.generateKey()
  }
}
