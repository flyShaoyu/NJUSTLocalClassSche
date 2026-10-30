package com.classsche.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from portal credentials; entire record encrypted, excluded from Android backups. */
internal class LibraryCredentialStore(context: Context, namespace: String = "library_credentials") {
  init { require(namespace.matches(Regex("[a-z_]+"))) }
  private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "$namespace.enc"))
  private val alias = "classsche_${namespace}_v1"
  private val aad = alias.toByteArray(Charsets.UTF_8)
  data class Credentials(val username: String, val password: String, val type: String) {
    fun validate() { require(username.isNotBlank() && password.isNotEmpty() && type in listOf("bar_no", "cert_no", "email")) }
    override fun toString() = "LibraryCredentials([redacted])"
  }
  @Synchronized fun load(): Credentials? = runCatching {
    val parts = file.readFully().toString(Charsets.UTF_8).split(':', limit = 3)
    require(parts.size == 3 && parts[0] == "v1")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
    cipher.updateAAD(aad)
    val data = JSONObject(String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), Charsets.UTF_8))
    Credentials(data.getString("username"), data.getString("password"), data.getString("type")).also { it.validate() }
  }.getOrNull()
  @Synchronized fun save(value: Credentials): Boolean {
    return runCatching {
      value.validate()
      val json = JSONObject().put("username", value.username).put("password", value.password).put("type", value.type).toString()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
      val payload = "v1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
      val output = file.startWrite()
      try { output.write(payload.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
      catch (error: Exception) { file.failWrite(output); throw error }
      true
    }.getOrDefault(false)
  }
  @Synchronized fun clear() { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) } }
  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    check(!store.containsAlias(alias)) { "Library credential key unavailable" }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256).setRandomizedEncryptionRequired(true).build())
    }.generateKey()
  }
}
