package com.classsche.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.security.MessageDigest

internal object GeneralEducationCatalogStore {
  private const val FILE = "general-education-catalog.json"
  private val lock = Any()
  // Android ICU requires both literal braces to be escaped (the desktop JVM is more permissive).
  private val pattern = Regex("""(?:let|const) generalCatalog = \{.*?\};""", RegexOption.DOT_MATCHES_ALL)
  private val preferred = listOf("人文素养类", "经济社会类", "经济社会类（四史）", "艺术审美类", "自然科技类", "自然技术类")

  fun save(context: Context, html: String, canCommit: () -> Boolean = { true }): JSONObject = synchronized(lock) {
    val catalog = GeneralEducationCatalogParser.parse(html)
    val categories = catalog.courses.map { it.category }.distinct()
    val root = JSONObject().put("schemaVersion", 1).put("title", catalog.title)
      .put("sourceUrl", GeneralEducationCatalogParser.SOURCE).put("imported", true).put("cacheSource", "mobile")
      .put("capturedOn", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString()).put("updatedAt", System.currentTimeMillis())
      .put("sourceSha256", MessageDigest.getInstance("SHA-256").digest(html.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
      .put("categories", JSONArray(preferred.filter { it in categories } + categories.filter { it !in preferred }))
      .put("courses", JSONArray().apply { catalog.courses.forEach { put(JSONObject().put("courseCode", it.courseCode).put("courseName", it.courseName).put("category", it.category)) } })
    val file = AtomicFile(File(context.filesDir, FILE))
    check(canCommit()) { "分类表更新已取消，原表已保留" }
    val output = file.startWrite()
    try {
      output.write(root.toString().toByteArray(Charsets.UTF_8))
      check(canCommit()) { "分类表更新已取消" }
      file.finishWrite(output)
    } catch (error: Exception) {
      file.failWrite(output)
      throw IllegalStateException("分类表保存失败，原表已保留：${error.message}", error)
    }
    root
  }

  fun read(context: Context): JSONObject? = synchronized(lock) {
    val file = AtomicFile(File(context.filesDir, FILE))
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized null
    try {
      val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
      check(root.getInt("schemaVersion") == 1 && root.getString("sourceUrl") == GeneralEducationCatalogParser.SOURCE)
      check(root.getString("title").contains("通识教育选修课分类汇总"))
      val courses = root.getJSONArray("courses")
      val categories = root.getJSONArray("categories")
      check(courses.length() in 1..10000 && categories.length() in 1..1000)
      val labels = (0 until categories.length()).map { categories.getString(it) }.toSet()
      for (index in 0 until courses.length()) {
        val item = courses.getJSONObject(index)
        check(item.getString("courseCode").matches(Regex("[A-Za-z0-9]{8}")) && item.getString("courseName").length in 1..300 && item.getString("category") in labels)
      }
      root
    } catch (error: Exception) { throw IllegalStateException("本机分类表缓存解析错误，暂用内置分类表，原文件已保留", error) }
  }

  fun scriptJson(catalog: JSONObject): String = catalog.toString().replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

  fun inject(context: Context, template: String): String = try {
    val catalog = read(context)
    if (catalog == null) pattern.replace(template) { it.value + "\ngeneralCatalog.mobileUpdateAvailable = true;" }
    else pattern.replace(template) { "let generalCatalog = ${scriptJson(catalog.put("mobileUpdateAvailable", true))};" }
  } catch (error: Exception) {
    AppDebugLog.append(context, "GENERAL_CATALOG", "FAIL", error.message.orEmpty())
    pattern.replace(template) { it.value + "\ngeneralCatalog.mobileUpdateAvailable = true;\ngeneralCatalog.cacheWarning = " + JSONObject.quote(error.message) + ";" }
  }
}
