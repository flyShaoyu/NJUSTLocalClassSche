package com.classsche.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

internal object LocalScheduleStore {
  private const val FILE = "local-schedules.json"
  private val lock = Any()
  private val days = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

  fun semester(context: Context): String = TimetableSemesterStore.resolveRenderedSemester(context).ifBlank {
    val today = LocalDate.now()
    if (today.monthValue >= 8) "${today.year}-${today.year + 1}-1" else "${today.year - 1}-${today.year}-2"
  }

  fun revision(context: Context): String = context.getSharedPreferences("local_schedules", Context.MODE_PRIVATE).getString("revision", "").orEmpty()

  fun read(context: Context): JSONArray = synchronized(lock) {
    val file = AtomicFile(File(context.filesDir, FILE))
    if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized JSONArray()
    try {
      val root = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
      check(root.optInt("version") == 1) { "不支持的数据版本" }
      root.getJSONArray("items")
    } catch (error: Exception) {
      throw IllegalStateException("本地日程解析错误：${error.message}；原文件已保留", error)
    }
  }

  private fun write(context: Context, items: JSONArray) {
    val file = AtomicFile(File(context.filesDir, FILE))
    val stream = file.startWrite()
    try {
      stream.write(JSONObject().put("version", 1).put("items", items).toString().toByteArray(Charsets.UTF_8))
      file.finishWrite(stream)
    } catch (error: Exception) {
      file.failWrite(stream)
      throw IllegalStateException("本地日程保存失败，原日程未覆盖", error)
    }
    context.getSharedPreferences("local_schedules", Context.MODE_PRIVATE).edit().putString("revision", UUID.randomUUID().toString()).apply()
  }

  fun parseWeeks(value: String): List<Int> {
    val normalized = value.replace('，', ',').replace('、', ',').replace("周", "").replace(Regex("\\s+"), "")
    require(normalized.matches(Regex("\\d+(?:-\\d+)?(?:,\\d+(?:-\\d+)?)*"))) { "周次格式不正确，请填写如 1-16 或 1,3,5-8" }
    return normalized.split(',').flatMap { token ->
      val parts = token.split('-').map { it.toInt() }
      val start = parts[0]
      val end = parts.getOrElse(1) { start }
      require(start in 1..30 && end in start..30) { "周次应在 1—30 内，结束周不能早于开始周" }
      (start..end).toList()
    }.distinct().sorted()
  }

  fun formatWeeks(weeks: List<Int>): String {
    val values = weeks.distinct().sorted()
    if (values.isEmpty()) return ""
    val ranges = mutableListOf<String>()
    var start = values.first()
    var end = start
    for (week in values.drop(1)) {
      if (week == end + 1) end = week else {
        ranges += if(start == end) "$start" else "$start-$end"
        start = week; end = week
      }
    }
    ranges += if(start == end) "$start" else "$start-$end"
    return ranges.joinToString(",")
  }

  fun validateTime(start: String, end: String): Pair<LocalTime, LocalTime> {
    require(start.matches(Regex("\\d{2}:\\d{2}")) && end.matches(Regex("\\d{2}:\\d{2}"))) { "请填写有效的起止时间" }
    val from = runCatching { LocalTime.parse(start) }.getOrElse { throw IllegalArgumentException("开始时间无效") }
    val to = runCatching { LocalTime.parse(end) }.getOrElse { throw IllegalArgumentException("结束时间无效") }
    require(to > from) { "结束时间必须晚于开始时间" }
    return from to to
  }

  fun save(context: Context, input: JSONObject): JSONObject = synchronized(lock) {
    val title = input.optString("title").trim()
    require(title.isNotEmpty() && title.length <= 100) { "日程名称应为 1—100 个字符" }
    val semester = input.optString("semester").ifBlank { semester(context) }
    require(semester.matches(Regex("\\d{4}-\\d{4}-[123]"))) { "学期格式不正确" }
    val weekday = input.optInt("weekday")
    require(weekday in 1..7) { "请选择星期" }
    val weeks = parseWeeks(input.optString("weeksText"))
    val start = input.optString("startTime")
    val end = input.optString("endTime")
    validateTime(start, end)
    val location = input.optString("location").trim()
    val notes = input.optString("notes").trim()
    require(location.length <= 200 && notes.length <= 2000) { "地点最多 200 字，备注最多 2000 字" }
    val items = read(context)
    val editingId = input.optString("id")
    val index = (0 until items.length()).firstOrNull { items.getJSONObject(it).optString("id") == editingId }
    require(editingId.isBlank() || index != null) { "要编辑的日程已不存在，请重新打开列表" }
    val item = JSONObject().put("id", editingId.ifBlank { UUID.randomUUID().toString() })
      .put("title", title).put("semester", semester).put("weekday", weekday)
      .put("weeks", JSONArray(weeks)).put("weeksText", formatWeeks(weeks))
      .put("startTime", start).put("endTime", end).put("location", location).put("notes", notes)
    if (index == null) items.put(item) else items.put(index, item)
    write(context, items)
    item
  }

  fun delete(context: Context, id: String) = synchronized(lock) {
    require(id.isNotBlank()) { "日程编号为空" }
    val items = read(context)
    val index = (0 until items.length()).firstOrNull { items.getJSONObject(it).optString("id") == id }
      ?: throw IllegalArgumentException("日程已不存在")
    items.remove(index)
    write(context, items)
  }

  fun courses(context: Context, semester: String = semester(context)): List<TimetableCourse> {
    val items = read(context)
    return (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("semester") == semester }.map { item ->
      val (start, end) = validateTime(item.getString("startTime"), item.getString("endTime"))
      // Grid placement covers the touched teaching slots; the real times remain
      // independent and are used in cards, details, the home page and reminders.
      val periods = TimetableScheduleHelper.periodSlots.filterKeys { it <= 13 }
        .filterValues { (a, b) -> LocalTime.parse(b) > start && LocalTime.parse(a) < end }.keys.sorted()
      val first = periods.firstOrNull() ?: if (start < LocalTime.of(8, 0)) 1 else if (start >= LocalTime.of(21, 25)) 13
        else TimetableScheduleHelper.periodSlots.filterKeys { it <= 13 }.entries.minByOrNull { (_, t) -> kotlin.math.abs(java.time.Duration.between(start, LocalTime.parse(t.first)).toMinutes()) }?.key ?: 1
      val last = periods.lastOrNull() ?: first
      val weeks = item.getJSONArray("weeks")
      TimetableCourse(
        courseName = item.getString("title"), weekday = days[item.getInt("weekday") - 1],
        periods = "$first-${last}节", classroom = item.optString("location"),
        weeks = formatWeeks((0 until weeks.length()).map { weeks.getInt(it) }) + "周",
        courseCode = "local:${item.getString("id")}", courseType = "本地日程",
        startTime = item.getString("startTime"), endTime = item.getString("endTime"), notes = item.optString("notes")
      )
    }
  }
}
