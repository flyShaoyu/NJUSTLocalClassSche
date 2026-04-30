package com.classsche.mobile

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class CourseOccurrence(
  val course: TimetableCourse,
  val date: LocalDate,
  val week: Int,
  val startPeriod: Int,
  val endPeriod: Int,
  val startTime: LocalTime,
  val endTime: LocalTime
) {
  val startAt: LocalDateTime = LocalDateTime.of(date, startTime)
  val endAt: LocalDateTime = LocalDateTime.of(date, endTime)
}

object TimetableScheduleHelper {
  private const val CACHE_JSON_FILE = "timetable.json"
  private val weekDays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
  private const val anchorWeek = 6
  private val anchorMonday: LocalDate = LocalDate.of(2026, 4, 6)

  val periodSlots = mapOf(
    1 to ("08:00" to "08:45"),
    2 to ("08:50" to "09:35"),
    3 to ("09:40" to "10:25"),
    4 to ("10:40" to "11:25"),
    5 to ("11:30" to "12:15"),
    6 to ("14:00" to "14:45"),
    7 to ("14:50" to "15:35"),
    8 to ("15:50" to "16:35"),
    9 to ("16:40" to "17:25"),
    10 to ("17:30" to "18:15"),
    11 to ("19:00" to "19:45"),
    12 to ("19:50" to "20:35"),
    13 to ("20:40" to "21:25"),
    14 to ("12:15" to "14:00")
  )

  private data class NormalizedCourse(
    val course: TimetableCourse,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: List<Int>
  )

  fun loadCoursesFromCacheJson(context: Context): List<TimetableCourse> {
    val cacheJsonFile = File(context.filesDir, CACHE_JSON_FILE)
    if (!cacheJsonFile.exists() || cacheJsonFile.length() <= 2L) return emptyList()
    val rawJson = runCatching { cacheJsonFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
    return runCatching {
      val array = JSONArray(rawJson)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.optJSONObject(index) ?: continue
          add(
            TimetableCourse(
              courseName = item.optString("courseName"),
              weekday = item.optString("weekday"),
              periods = item.optString("periods"),
              classroom = item.optString("classroom"),
              weeks = item.optString("weeks"),
              teacher = item.optString("teacher"),
              courseCode = item.optString("courseCode"),
              courseSequence = item.optString("courseSequence"),
              courseType = item.optString("courseType"),
              rawText = item.optString("rawText")
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  fun parseWeeks(weeksText: String): List<Int> {
    val matches = Regex("""\d+(?:-\d+)?""").findAll(weeksText)
    return matches.flatMap { match ->
      val token = match.value
      if ("-" in token) {
        val (startText, endText) = token.split("-")
        (startText.toInt()..endText.toInt()).asSequence()
      } else {
        sequenceOf(token.toInt())
      }
    }.toList()
  }

  fun formatMajorPeriodLabel(startPeriod: Int, endPeriod: Int): String {
    if (startPeriod == 14 || endPeriod == 14) return "线上"
    val majorIndex = when (startPeriod) {
      in 1..3 -> 1
      in 4..5 -> 2
      in 6..7 -> 3
      in 8..10 -> 4
      in 11..13 -> 5
      else -> startPeriod
    }
    return "第${majorIndex}大节"
  }

  fun findNextCourse(courses: List<TimetableCourse>, now: LocalDateTime = LocalDateTime.now(), daysAhead: Int = 14): CourseOccurrence? {
    if (courses.isEmpty()) return null
    val normalized = normalizeCourses(courses)
    for (offset in 0..daysAhead) {
      val date = now.toLocalDate().plusDays(offset.toLong())
      val weekday = weekDays[(date.dayOfWeek.value - 1) % weekDays.size]
      val week = anchorWeek + (ChronoUnit.DAYS.between(anchorMonday, date) / 7).toInt()
      val candidate = normalized
        .asSequence()
        .filter { it.course.weekday == weekday && (it.weeks.isEmpty() || it.weeks.contains(week)) }
        .sortedWith(compareBy<NormalizedCourse> { it.startPeriod }.thenBy { it.endPeriod })
        .mapNotNull { normalizedCourse ->
          val start = parseTime(periodSlots[normalizedCourse.startPeriod]?.first ?: return@mapNotNull null)
          val end = parseTime(periodSlots[normalizedCourse.endPeriod]?.second ?: return@mapNotNull null)
          CourseOccurrence(
            course = normalizedCourse.course,
            date = date,
            week = week,
            startPeriod = normalizedCourse.startPeriod,
            endPeriod = normalizedCourse.endPeriod,
            startTime = start,
            endTime = end
          )
        }
        .firstOrNull { occurrence -> occurrence.endAt >= now }
      if (candidate != null) return candidate
    }
    return null
  }

  fun findNotificationWindowCourse(
    courses: List<TimetableCourse>,
    leadMinutes: Int,
    now: LocalDateTime = LocalDateTime.now(),
    daysAhead: Int = 14
  ): CourseOccurrence? {
    if (courses.isEmpty()) return null
    val normalized = normalizeCourses(courses)
    for (offset in 0..daysAhead) {
      val date = now.toLocalDate().plusDays(offset.toLong())
      val weekday = weekDays[(date.dayOfWeek.value - 1) % weekDays.size]
      val week = anchorWeek + (ChronoUnit.DAYS.between(anchorMonday, date) / 7).toInt()
      val candidate = normalized
        .asSequence()
        .filter { it.course.weekday == weekday && (it.weeks.isEmpty() || it.weeks.contains(week)) }
        .sortedWith(compareBy<NormalizedCourse> { it.startPeriod }.thenBy { it.endPeriod })
        .mapNotNull { normalizedCourse ->
          val start = parseTime(periodSlots[normalizedCourse.startPeriod]?.first ?: return@mapNotNull null)
          val end = parseTime(periodSlots[normalizedCourse.endPeriod]?.second ?: return@mapNotNull null)
          CourseOccurrence(
            course = normalizedCourse.course,
            date = date,
            week = week,
            startPeriod = normalizedCourse.startPeriod,
            endPeriod = normalizedCourse.endPeriod,
            startTime = start,
            endTime = end
          )
        }
        .firstOrNull { occurrence ->
          val reminderAt = occurrence.startAt.minusMinutes(leadMinutes.toLong())
          !now.isBefore(reminderAt) && !now.isAfter(occurrence.endAt)
        }
      if (candidate != null) return candidate
    }
    return null
  }

  fun formatCourseTimeRange(occurrence: CourseOccurrence): String =
    "${formatTime(occurrence.startTime)}-${formatTime(occurrence.endTime)}"

  fun formatCourseStartTime(occurrence: CourseOccurrence): String =
    "${occurrence.date.monthValue}月${occurrence.date.dayOfMonth}日 ${formatTime(occurrence.startTime)}"

  private fun normalizeCourses(courses: List<TimetableCourse>): List<NormalizedCourse> {
    return courses.map { course ->
      val match = Regex("""(\d+)(?:-(\d+))?""").find(course.periods)
      NormalizedCourse(
        course = course,
        startPeriod = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1,
        endPeriod = match?.groupValues?.getOrNull(2)?.toIntOrNull()
          ?: match?.groupValues?.getOrNull(1)?.toIntOrNull()
          ?: 1,
        weeks = parseWeeks(course.weeks)
      )
    }
  }

  private fun parseTime(value: String): LocalTime =
    runCatching { LocalTime.parse(value) }.getOrElse { LocalTime.MIN }

  private fun formatTime(value: LocalTime): String = value.toString().take(5)
}
