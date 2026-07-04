package com.classsche.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object HeadlessScoreSyncManager {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_USERNAME = "username"
  private const val PREF_PASSWORD = "password"
  private const val LOGIN_URL = "http://202.119.81.113:8080"
  private const val TIMETABLE_URL = "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do"
  private const val SCORE_LIST_URL = "http://202.119.81.112:9080/njlgdx/kscj/cjcx_list"
  private const val SCORE_JSON_FILE = "score-list.json"
  private const val SCORE_UPDATE_META_FILE = "score-update-meta.json"
  private const val SCORE_UPDATE_CHANNEL_ID = "classsche_score_update_v1"
  private const val SCORE_UPDATE_NOTIFICATION_ID = 3101

  enum class Status {
    SUCCESS,
    SKIPPED,
    FAILED
  }

  data class SyncResult(
    val status: Status,
    val scoreCount: Int = 0,
    val updatedCount: Int = 0,
    val message: String = ""
  )

  private val executor = Executors.newSingleThreadExecutor()
  @Volatile private var syncInProgress = false

  fun runSync(
    context: Context,
    reason: String,
    onComplete: ((SyncResult) -> Unit)? = null
  ) {
    val appContext = context.applicationContext
    synchronized(this) {
      if (syncInProgress) {
        log(appContext, "HEADLESS_SCORE_SYNC", "INFO", "已有同步在进行中，跳过本次触发 reason=$reason")
        onComplete?.invoke(SyncResult(Status.SKIPPED, message = "已有同步在进行中"))
        return
      }
      syncInProgress = true
    }

    executor.execute {
      val result = try {
        performSync(appContext, reason)
      } catch (error: Exception) {
        log(appContext, "HEADLESS_SCORE_SYNC", "FAIL", error.message ?: "unknown")
        SyncResult(Status.FAILED, message = error.message ?: "unknown")
      } finally {
        synchronized(this) {
          syncInProgress = false
        }
      }
      onComplete?.invoke(result)
    }
  }

  private fun performSync(context: Context, reason: String): SyncResult {
    log(context, "HEADLESS_SCORE_SYNC", "START", "开始纯 HTTP 成绩同步 reason=$reason")
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val username = prefs.getString(PREF_USERNAME, "").orEmpty().trim()
    val password = prefs.getString(PREF_PASSWORD, "").orEmpty().trim()
    if (username.isBlank() || password.isBlank()) {
      log(context, "HEADLESS_SCORE_SYNC", "WARN", "缺少账号或密码，跳过本次同步")
      return SyncResult(Status.SKIPPED, message = "缺少账号或密码")
    }

    val loginResult = HeadlessLoginClient(logger = { scope, status, message ->
      log(context, scope, status, message)
    }).login(
      loginUrl = LOGIN_URL,
      timetableUrl = TIMETABLE_URL,
      username = username,
      password = password,
      recognizeCaptcha = ::recognizeCaptchaTextSync
    )
    log(
      context,
      "HEADLESS_SCORE_SYNC",
      "INFO",
      "纯 HTTP 登录完成，验证码尝试=${loginResult.captchaAttempts} cookieCount=${loginResult.cookies.size}"
    )

    val latestScores = fetchScoreRecordsWithCookies(context, loginResult.cookies)
    val scoreFile = File(context.filesDir, SCORE_JSON_FILE)
    val previousScores = readScoreArrayFromFile(scoreFile)
    scoreFile.writeText(latestScores.toString(), Charsets.UTF_8)

    val updatedItems = detectUpdatedScoreItems(previousScores, latestScores)
    val hasNewUpdates = previousScores.length() > 0 && updatedItems.isNotEmpty()
    val pendingItems = persistScoreUpdateMeta(context, hasNewUpdates, updatedItems)
    if (hasNewUpdates) {
      log(context, "SCORE_UPDATE", "SUCCESS", "检测到 ${updatedItems.size} 条成绩更新")
      showScoreUpdateNotification(context, updatedItems)
    } else if (pendingItems.isNotEmpty()) {
      log(context, "SCORE_UPDATE", "INFO", "本次没有新增成绩变动，保留 ${pendingItems.size} 条未读更新")
    } else {
      log(context, "SCORE_UPDATE", "INFO", "本次未检测到新的成绩变动")
    }
    log(context, "HEADLESS_SCORE_SYNC", "SUCCESS", "成绩同步完成，共 ${latestScores.length()} 条")
    return SyncResult(
      status = Status.SUCCESS,
      scoreCount = latestScores.length(),
      updatedCount = updatedItems.size,
      message = "ok"
    )
  }

  private fun fetchScoreRecordsWithCookies(
    context: Context,
    cookies: Map<String, String>
  ): JSONArray {
    log(context, "HEADLESS_SCORE_FETCH", "START", "开始独立拉取成绩页")
    val connection = URL(SCORE_LIST_URL).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "GET"
      connection.useCaches = false
      connection.instanceFollowRedirects = true
      connection.connectTimeout = 10000
      connection.readTimeout = 10000
      connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
      connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
      connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari/537.36")
      connection.setRequestProperty("Referer", TIMETABLE_URL)
      if (cookies.isNotEmpty()) {
        connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
      }
      val responseCode = connection.responseCode
      log(
        context,
        "HEADLESS_SCORE_FETCH",
        if (responseCode in 200..299) "INFO" else "WARN",
        "响应码=$responseCode contentType=${connection.contentType ?: "-"}"
      )
      val bytes = (if (responseCode in 200..299) connection.inputStream else connection.errorStream ?: connection.inputStream)
        .use { it.readBytes() }
      val document = org.jsoup.Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, SCORE_LIST_URL)
      log(context, "HEADLESS_SCORE_FETCH", "INFO", "页面标题=${document.title().ifBlank { "-" }}")
      return parseScoreDocument(context, document)
    } finally {
      connection.disconnect()
    }
  }

  private fun parseScoreDocument(
    context: Context,
    document: org.jsoup.nodes.Document
  ): JSONArray {
    val rows = document.select("#dataList tr")
    if (rows.isEmpty()) {
      val title = document.title()
      log(context, "HEADLESS_SCORE_PARSE", "FAIL", "未找到成绩表格，页面标题=${title.ifBlank { "-" }}")
      if (title.contains("登录") || title.contains("login", ignoreCase = true)) {
        throw IllegalStateException("未获取到成绩列表，会话可能已过期。当前页面: $title")
      }
      throw IllegalStateException("未在成绩页面中找到 #dataList 表格。当前页面: $title")
    }

    log(context, "HEADLESS_SCORE_PARSE", "INFO", "检测到成绩表格行数=${rows.size}")
    val result = JSONArray()
    rows.drop(1).forEach { row ->
      val cells = row.select("> td")
      if (cells.size < 11) return@forEach

      val item = JSONObject().apply {
        put("index", cleanInlineText(cells.getOrNull(0)?.text().orEmpty()).toIntOrNull() ?: (result.length() + 1))
        put("semester", cleanInlineText(cells.getOrNull(1)?.text().orEmpty()))
        put("courseCode", cleanInlineText(cells.getOrNull(2)?.text().orEmpty()))
        put("courseName", cleanInlineText(cells.getOrNull(3)?.text().orEmpty()))
        put("score", cleanInlineText(cells.getOrNull(4)?.text().orEmpty()))
        put("scoreIdentifier", cleanInlineText(cells.getOrNull(5)?.text().orEmpty()))
        put("credits", cleanInlineText(cells.getOrNull(6)?.text().orEmpty()))
        put("totalHours", cleanInlineText(cells.getOrNull(7)?.text().orEmpty()))
        put("assessmentMethod", cleanInlineText(cells.getOrNull(8)?.text().orEmpty()))
        put("courseAttribute", cleanInlineText(cells.getOrNull(9)?.text().orEmpty()))
        put("courseNature", cleanInlineText(cells.getOrNull(10)?.text().orEmpty()))
        put("isHighlighted", cells.getOrNull(4)?.attr("style")?.contains("red", ignoreCase = true) == true)
        put(
          "rawText",
          buildString {
            cells.forEachIndexed { cellIndex, cell ->
              if (cellIndex > 0) append('\n')
              append(cleanInlineText(cell.text()))
            }
          }
        )
      }

      val courseName = item.optString("courseName")
      val semester = item.optString("semester")
      val score = item.optString("score")
      if (courseName.isBlank() && semester.isBlank() && score.isBlank()) {
        return@forEach
      }
      result.put(item)
    }
    log(context, "HEADLESS_SCORE_PARSE", "SUCCESS", "成绩解析完成，共 ${result.length()} 条")
    return result
  }

  private fun readScoreArrayFromFile(file: File): JSONArray {
    if (!file.exists() || file.length() <= 0L) return JSONArray()
    return runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrElse { JSONArray() }
  }

  private fun detectUpdatedScoreItems(previousScores: JSONArray, latestScores: JSONArray): List<JSONObject> {
    if (previousScores.length() <= 0 || latestScores.length() <= 0) return emptyList()
    val previousFingerprints = buildSet {
      for (index in 0 until previousScores.length()) {
        val item = previousScores.optJSONObject(index) ?: continue
        add(scoreFingerprint(item))
      }
    }
    val updates = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    for (index in 0 until latestScores.length()) {
      val item = latestScores.optJSONObject(index) ?: continue
      val fingerprint = scoreFingerprint(item)
      if (fingerprint in previousFingerprints || !seen.add(fingerprint)) continue
      updates += buildScoreUpdateItem(item)
    }
    return updates
  }

  private fun persistScoreUpdateMeta(
    context: Context,
    pending: Boolean,
    updatedItems: List<JSONObject>
  ): List<JSONObject> {
    val file = File(context.filesDir, SCORE_UPDATE_META_FILE)
    val currentMeta = readScoreUpdateMeta(context)
    val existingPendingItems = if (currentMeta.optBoolean("pending")) {
      readScoreUpdateItems(currentMeta)
    } else {
      emptyList()
    }
    val nextPendingItems = when {
      pending && updatedItems.isNotEmpty() -> mergePendingScoreUpdateItems(existingPendingItems, updatedItems)
      existingPendingItems.isNotEmpty() -> existingPendingItems
      pending -> updatedItems
      else -> emptyList()
    }
    val payload = JSONObject().apply {
      put("pending", nextPendingItems.isNotEmpty())
      put(
        "updatedAt",
        when {
          nextPendingItems.isEmpty() -> System.currentTimeMillis()
          pending && updatedItems.isNotEmpty() -> System.currentTimeMillis()
          currentMeta.optLong("updatedAt") > 0L -> currentMeta.optLong("updatedAt")
          else -> System.currentTimeMillis()
        }
      )
      put("items", JSONArray().apply { nextPendingItems.forEach(::put) })
    }
    file.writeText(payload.toString(), Charsets.UTF_8)
    return nextPendingItems
  }

  private fun readScoreUpdateMeta(context: Context): JSONObject {
    val file = File(context.filesDir, SCORE_UPDATE_META_FILE)
    if (!file.exists() || file.length() <= 0L) {
      return JSONObject().apply {
        put("pending", false)
        put("items", JSONArray())
      }
    }
    return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse {
      JSONObject().apply {
        put("pending", false)
        put("items", JSONArray())
      }
    }
  }

  private fun readScoreUpdateItems(meta: JSONObject): List<JSONObject> {
    val array = meta.optJSONArray("items") ?: return emptyList()
    return buildList {
      for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(item)
      }
    }
  }

  private fun mergePendingScoreUpdateItems(
    existingItems: List<JSONObject>,
    newItems: List<JSONObject>
  ): List<JSONObject> {
    val merged = LinkedHashMap<String, JSONObject>()
    existingItems.forEach { item ->
      val fingerprint = item.optString("fingerprint").ifBlank { scoreFingerprint(item) }
      if (fingerprint.isNotBlank()) {
        merged[fingerprint] = item
      }
    }
    newItems.forEach { item ->
      val fingerprint = item.optString("fingerprint").ifBlank { scoreFingerprint(item) }
      if (fingerprint.isNotBlank()) {
        merged[fingerprint] = item
      }
    }
    return merged.values.toList()
  }

  private fun showScoreUpdateNotification(context: Context, updatedItems: List<JSONObject>) {
    if (!hasPostNotificationPermission(context)) {
      log(context, "SCORE_UPDATE", "WARN", "系统未授予通知权限，跳过成绩更新通知")
      return
    }
    ensureScoreUpdateNotificationChannel(context)
    val launchIntent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val title = if (updatedItems.size == 1) "成绩有更新" else "成绩有 ${updatedItems.size} 项更新"
    val preview = updatedItems.take(3).joinToString("；") { item ->
      "${item.optString("courseName").ifBlank { "未命名课程" }} ${item.optString("score").ifBlank { "--" }}"
    }
    val bigText = buildString {
      append("检测到新的成绩变动。")
      if (preview.isNotBlank()) append("\n").append(preview)
      append("\n点开成绩查询后会高亮本次更新项。")
    }
    context.getSystemService(NotificationManager::class.java).notify(
      SCORE_UPDATE_NOTIFICATION_ID,
      NotificationCompat.Builder(context, SCORE_UPDATE_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(title)
        .setContentText(preview.ifBlank { "点击查看详情" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()
    )
  }

  private fun ensureScoreUpdateNotificationChannel(context: Context) {
    val channel = NotificationChannel(
      SCORE_UPDATE_CHANNEL_ID,
      "成绩更新",
      NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
      description = "用于提示成绩查询中出现新的成绩变动"
      lockscreenVisibility = Notification.VISIBILITY_PRIVATE
      setShowBadge(true)
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  private fun hasPostNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  private fun scoreFingerprint(item: JSONObject): String {
    return listOf(
      item.optString("semester"),
      item.optString("courseCode"),
      item.optString("courseName"),
      item.optString("score"),
      item.optString("scoreIdentifier"),
      item.optString("credits"),
      item.optString("courseAttribute"),
      item.optString("courseNature")
    ).joinToString("|") { normalizeScoreMetaText(it) }
  }

  private fun buildScoreUpdateItem(item: JSONObject): JSONObject {
    return JSONObject().apply {
      put("fingerprint", scoreFingerprint(item))
      put("semester", item.optString("semester"))
      put("courseCode", item.optString("courseCode"))
      put("courseName", item.optString("courseName"))
      put("score", item.optString("score"))
      put("scoreIdentifier", item.optString("scoreIdentifier"))
      put("courseAttribute", item.optString("courseAttribute"))
      put("courseNature", item.optString("courseNature"))
    }
  }

  private fun normalizeScoreMetaText(value: String?): String =
    value.orEmpty().replace(Regex("""\s+"""), "").trim()

  private fun normalizeText(value: String): String =
    value
      .replace('\u00A0', ' ')
      .replace("&nbsp;", " ")
      .replace("\r", "\n")
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex("\\n{3,}"), "\n\n")
      .trim()

  private fun cleanInlineText(value: String): String =
    normalizeText(value).replace(Regex("\\s*\\n\\s*"), " ")

  private fun recognizeCaptchaTextSync(bitmap: Bitmap): String? {
    val result = AtomicReference<String?>()
    val error = AtomicReference<Throwable?>()
    val latch = CountDownLatch(1)
    val processedBitmap = preprocessCaptcha(bitmap)
    val image = InputImage.fromBitmap(processedBitmap, 0)
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(image)
      .addOnSuccessListener { visionText ->
        result.set(visionText.text.replace(Regex("[^a-zA-Z0-9]"), ""))
        latch.countDown()
      }
      .addOnFailureListener { throwable ->
        error.set(throwable)
        latch.countDown()
      }

    if (!latch.await(12, TimeUnit.SECONDS)) {
      throw IllegalStateException("验证码识别超时")
    }
    error.get()?.let { throw IllegalStateException(it.message ?: "验证码识别失败", it) }
    return result.get()
  }

  private fun preprocessCaptcha(src: Bitmap): Bitmap {
    val scale = 3f
    val scaledWidth = (src.width * scale).toInt()
    val scaledHeight = (src.height * scale).toInt()
    val scaledBitmap = Bitmap.createScaledBitmap(src, scaledWidth, scaledHeight, true)

    val result = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    val paint = android.graphics.Paint()
    val colorMatrix = android.graphics.ColorMatrix().apply { setSaturation(0f) }
    paint.colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
    canvas.drawBitmap(scaledBitmap, 0f, 0f, paint)

    val pixels = IntArray(scaledWidth * scaledHeight)
    result.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
    for (i in pixels.indices) {
      val p = pixels[i]
      val r = Color.red(p)
      val g = Color.green(p)
      val b = Color.blue(p)
      val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt()
      pixels[i] = if (gray > 165) Color.WHITE else Color.BLACK
    }
    result.setPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
    return result
  }

  private fun log(context: Context, scope: String, status: String, message: String) {
    AppDebugLog.append(context, scope, status, message)
  }
}
