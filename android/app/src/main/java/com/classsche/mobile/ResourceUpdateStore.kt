package com.classsche.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object ResourceUpdateStore {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_ACTIVE_RESOURCE_VERSION = "active_resource_version"
  private const val UPDATE_DIR_NAME = "resource-updates"
  private const val DOWNLOAD_DIR_NAME = "resource-update-downloads"
  private const val CACHE_META_FILE = "cache-meta.json"
  private const val UPDATE_USER_AGENT = "Mozilla/5.0 ClassScheMobile"
  private const val CONNECT_TIMEOUT_MS = 10000
  private const val READ_TIMEOUT_MS = 30000

  data class ApplyResult(
    val applied: Boolean,
    val version: String,
    val message: String
  )

  fun baseUrl(context: Context): String {
    return activeRoot(context)?.toURI()?.toString() ?: "file:///android_asset/"
  }

  fun activeVersion(context: Context): String? {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(PREF_ACTIVE_RESOURCE_VERSION, null)
      ?.takeIf { it.isNotBlank() }
      ?.takeIf { activeRootForVersion(context, it).isDirectory }
  }

  fun installedResourceVersion(context: Context): String? {
    return activeVersion(context) ?: bundledResourceVersion(context)
  }

  fun bundledResourceVersion(context: Context): String? {
    return runCatching {
      context.assets.open(CACHE_META_FILE).bufferedReader(Charsets.UTF_8).use { reader ->
        val meta = JSONObject(reader.readText())
        meta.optString("resourceVersion")
          .ifBlank { meta.optString("exportedAt") }
          .takeIf { it.isNotBlank() }
      }
    }.getOrNull()
  }

  fun readText(context: Context, assetPath: String): String? {
    return openStream(context, assetPath)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
  }

  fun openStream(context: Context, assetPath: String): InputStream? {
    val normalizedPath = normalizeAssetPath(assetPath) ?: return null
    activeRoot(context)?.let { root ->
      resolveInside(root, normalizedPath)
        ?.takeIf { it.isFile }
        ?.let { return it.inputStream() }
    }

    return runCatching {
      context.assets.open(normalizedPath).use { input ->
        ByteArrayInputStream(input.readBytes())
      }
    }.getOrNull()
  }

  @Throws(IOException::class)
  fun checkAndApplyUpdate(
    context: Context,
    manifestUrl: String,
    currentAppVersionCode: Long,
    log: (status: String, message: String) -> Unit
  ): ApplyResult {
    val manifest = JSONObject(fetchText(manifestUrl))
    val resourceVersion = manifest.optString("resourceVersion").trim()
    if (resourceVersion.isBlank()) {
      throw IOException("manifest missing resourceVersion")
    }

    val minAppVersionCode = manifest.optLong("minAppVersionCode", 0L)
    if (minAppVersionCode > currentAppVersionCode) {
      return ApplyResult(
        applied = false,
        version = resourceVersion,
        message = "resource $resourceVersion requires app versionCode >= $minAppVersionCode"
      )
    }

    val currentVersion = installedResourceVersion(context).orEmpty()
    if (currentVersion.isNotBlank() && compareResourceVersions(resourceVersion, currentVersion) <= 0) {
      return ApplyResult(
        applied = false,
        version = resourceVersion,
        message = "current resource is already up to date: $currentVersion"
      )
    }

    val packageInfo = manifest.optJSONObject("package")
      ?: throw IOException("manifest missing package")
    val packageUrl = packageInfo.optString("url").trim()
      .ifBlank { packageInfo.optString("fileName").trim() }
    if (packageUrl.isBlank()) {
      throw IOException("manifest missing package.url")
    }

    val resolvedPackageUrl = URL(URL(manifestUrl), packageUrl).toString()
    val expectedPackageSize = packageInfo.optLong("sizeBytes", -1L)
    val expectedPackageSha256 = packageInfo.optString("sha256").trim()
    val expectedFiles = parseExpectedFiles(manifest.optJSONArray("files"))
    if (expectedFiles.isEmpty()) {
      throw IOException("manifest missing files")
    }

    log("INFO", "found resource $resourceVersion, downloading package")
    val downloadDir = File(context.cacheDir, DOWNLOAD_DIR_NAME).apply { mkdirs() }
    val zipFile = File(downloadDir, "$resourceVersion.zip")
    downloadFile(resolvedPackageUrl, zipFile)

    if (expectedPackageSize >= 0L && zipFile.length() != expectedPackageSize) {
      zipFile.delete()
      throw IOException("package size mismatch: ${zipFile.length()} != $expectedPackageSize")
    }

    if (expectedPackageSha256.isNotBlank()) {
      val actualSha256 = sha256(zipFile)
      if (!actualSha256.equals(expectedPackageSha256, ignoreCase = true)) {
        zipFile.delete()
        throw IOException("package sha256 mismatch")
      }
    }

    val updateRoot = updatesRoot(context).apply { mkdirs() }
    val stagingDir = File(updateRoot, "$resourceVersion.tmp")
    val finalDir = activeRootForVersion(context, resourceVersion)
    stagingDir.deleteRecursively()
    finalDir.deleteRecursively()
    stagingDir.mkdirs()

    unzipPackage(zipFile, stagingDir, expectedFiles.keys)
    verifyExtractedFiles(stagingDir, expectedFiles)

    if (!stagingDir.renameTo(finalDir)) {
      stagingDir.copyRecursively(finalDir, overwrite = true)
      stagingDir.deleteRecursively()
    }

    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(PREF_ACTIVE_RESOURCE_VERSION, resourceVersion)
      .apply()
    pruneOldVersions(updateRoot, resourceVersion)
    zipFile.delete()

    return ApplyResult(
      applied = true,
      version = resourceVersion,
      message = "activated resource $resourceVersion"
    )
  }

  @Throws(IOException::class)
  fun checkLatestGiteeReleaseUpdate(
    context: Context,
    releaseApiUrl: String,
    manifestFileName: String,
    currentAppVersionCode: Long,
    log: (status: String, message: String) -> Unit
  ): ApplyResult {
    val release = JSONObject(fetchText(releaseApiUrl))
    val assets = release.optJSONArray("assets")
      ?: throw IOException("Gitee release missing assets")
    var manifestUrl: String? = null
    for (index in 0 until assets.length()) {
      val asset = assets.optJSONObject(index) ?: continue
      if (asset.optString("name").trim() != manifestFileName) continue
      manifestUrl = asset.optString("browser_download_url").trim()
        .ifBlank { asset.optString("download_url").trim() }
      break
    }
    if (manifestUrl.isNullOrBlank()) {
      throw IOException("Gitee release has no $manifestFileName asset")
    }
    return checkAndApplyUpdate(context, manifestUrl, currentAppVersionCode, log)
  }

  private fun activeRoot(context: Context): File? {
    val version = activeVersion(context) ?: return null
    return activeRootForVersion(context, version).takeIf { it.isDirectory }
  }

  private fun activeRootForVersion(context: Context, version: String): File =
    File(updatesRoot(context), version)

  private fun updatesRoot(context: Context): File =
    File(context.filesDir, UPDATE_DIR_NAME)

  private fun normalizeAssetPath(assetPath: String): String? {
    val normalized = assetPath
      .replace('\\', '/')
      .removePrefix("./")
      .trim()
    if (normalized.isBlank()) return null
    if (normalized.startsWith("/") || normalized.contains("../")) return null
    return normalized
  }

  private fun resolveInside(root: File, relativePath: String): File? {
    val rootCanonical = root.canonicalFile
    val candidate = File(rootCanonical, relativePath).canonicalFile
    return candidate.takeIf {
      it.path == rootCanonical.path || it.path.startsWith(rootCanonical.path + File.separator)
    }
  }

  private data class ExpectedFile(
    val path: String,
    val sizeBytes: Long,
    val sha256: String
  )

  private fun parseExpectedFiles(filesArray: JSONArray?): Map<String, ExpectedFile> {
    if (filesArray == null) return emptyMap()
    val result = linkedMapOf<String, ExpectedFile>()
    for (index in 0 until filesArray.length()) {
      val item = filesArray.optJSONObject(index) ?: continue
      val path = normalizeAssetPath(item.optString("path")) ?: continue
      val sha256 = item.optString("sha256").trim()
      if (sha256.isBlank()) continue
      result[path] = ExpectedFile(
        path = path,
        sizeBytes = item.optLong("sizeBytes", -1L),
        sha256 = sha256
      )
    }
    return result
  }

  @Throws(IOException::class)
  private fun fetchText(url: String): String {
    val connection = openConnection(url, "application/json")
    try {
      connection.connect()
      if (connection.responseCode !in 200..299) {
        throw IOException("HTTP ${connection.responseCode}")
      }
      return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  @Throws(IOException::class)
  private fun downloadFile(fileUrl: String, targetFile: File) {
    val connection = openConnection(fileUrl, "*/*")
    try {
      connection.connect()
      if (connection.responseCode !in 200..299) {
        throw IOException("HTTP ${connection.responseCode}")
      }

      BufferedInputStream(connection.inputStream).use { input ->
        BufferedOutputStream(targetFile.outputStream()).use { output ->
          input.copyTo(output)
          output.flush()
        }
      }
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: String, accept: String): HttpURLConnection {
    return (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "GET"
      instanceFollowRedirects = true
      connectTimeout = CONNECT_TIMEOUT_MS
      readTimeout = READ_TIMEOUT_MS
      setRequestProperty("User-Agent", UPDATE_USER_AGENT)
      setRequestProperty("Accept", accept)
    }
  }

  @Throws(IOException::class)
  private fun unzipPackage(zipFile: File, targetDir: File, allowedPaths: Set<String>) {
    val extractedPaths = linkedSetOf<String>()
    ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val normalizedPath = normalizeAssetPath(entry.name)
          ?: throw IOException("package contains invalid path: ${entry.name}")
        if (entry.isDirectory) {
          zip.closeEntry()
          continue
        }
        if (!allowedPaths.contains(normalizedPath)) {
          throw IOException("package contains file not declared by manifest: $normalizedPath")
        }
        if (!extractedPaths.add(normalizedPath)) {
          throw IOException("package contains duplicate file: $normalizedPath")
        }

        val targetFile = resolveInside(targetDir, normalizedPath)
          ?: throw IOException("package path escapes target dir: $normalizedPath")
        targetFile.parentFile?.mkdirs()
        BufferedOutputStream(targetFile.outputStream()).use { output ->
          zip.copyTo(output)
          output.flush()
        }
        zip.closeEntry()
      }
    }

    val missingPaths = allowedPaths - extractedPaths
    if (missingPaths.isNotEmpty()) {
      throw IOException("package missing files: ${missingPaths.take(3).joinToString(", ")}")
    }
  }

  @Throws(IOException::class)
  private fun verifyExtractedFiles(root: File, expectedFiles: Map<String, ExpectedFile>) {
    expectedFiles.values.forEach { expected ->
      val file = resolveInside(root, expected.path)
        ?.takeIf { it.isFile }
        ?: throw IOException("resource file missing: ${expected.path}")
      if (expected.sizeBytes >= 0L && file.length() != expected.sizeBytes) {
        throw IOException("resource file size mismatch: ${expected.path}")
      }
      val actualSha256 = sha256(file)
      if (!actualSha256.equals(expected.sha256, ignoreCase = true)) {
        throw IOException("resource file sha256 mismatch: ${expected.path}")
      }
    }
  }

  private fun pruneOldVersions(updateRoot: File, activeVersion: String) {
    updateRoot.listFiles()?.forEach { file ->
      if (file.name != activeVersion && file.name != "$activeVersion.tmp") {
        file.deleteRecursively()
      }
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun compareResourceVersions(left: String, right: String): Int {
    if (left == right) return 0
    val leftParts = Regex("""\d+""").findAll(left).map { it.value.toLongOrNull() ?: 0L }.toList()
    val rightParts = Regex("""\d+""").findAll(right).map { it.value.toLongOrNull() ?: 0L }.toList()
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
      val leftValue = leftParts.getOrElse(index) { 0L }
      val rightValue = rightParts.getOrElse(index) { 0L }
      if (leftValue != rightValue) {
        return leftValue.compareTo(rightValue)
      }
    }
    return left.compareTo(right)
  }
}
