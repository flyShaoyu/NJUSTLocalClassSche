package com.classsche.mobile

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class ExamOccurrence(
  val exam: ExamArrangement,
  val date: LocalDate,
  val startTime: LocalTime,
  val endTime: LocalTime
) {
  val startAt: LocalDateTime = LocalDateTime.of(date, startTime)
  val endAt: LocalDateTime = LocalDateTime.of(date, endTime)
  val notificationKey: String =
    listOf(exam.courseCode, exam.examTime, exam.examRoom, exam.seatNumber).joinToString("|")
}

object ExamNotificationHelper {
  private const val EXAM_JSON_FILE = "exam-list.json"
  private val examTimeRegex = Regex("""^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})~(\d{2}:\d{2})""")

  fun loadExamOccurrences(context: Context): List<ExamOccurrence> {
    val rawJson = readExamJson(context) ?: return emptyList()
    return runCatching {
      val array = JSONArray(rawJson)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.optJSONObject(index) ?: continue
          val exam = ExamArrangement(
            index = item.optInt("index", index + 1),
            examSession = item.optString("examSession"),
            courseCode = item.optString("courseCode"),
            courseName = item.optString("courseName"),
            examTime = item.optString("examTime"),
            examRoom = item.optString("examRoom"),
            seatNumber = item.optString("seatNumber"),
            teacher = item.optString("teacher"),
            rawText = item.optString("rawText")
          )
          parseOccurrence(exam)?.let(::add)
        }
      }.sortedWith(compareBy<ExamOccurrence> { it.startAt }.thenBy { it.exam.courseName })
    }.getOrDefault(emptyList())
  }

  fun findNextExam(
    exams: List<ExamOccurrence>,
    now: LocalDateTime = LocalDateTime.now()
  ): ExamOccurrence? =
    exams.firstOrNull { occurrence -> occurrence.endAt >= now }

  fun findNotificationWindowExam(
    exams: List<ExamOccurrence>,
    leadMinutes: Int,
    now: LocalDateTime = LocalDateTime.now()
  ): ExamOccurrence? =
    exams.firstOrNull { occurrence ->
      val reminderAt = occurrence.startAt.minusMinutes(leadMinutes.toLong())
      !now.isBefore(reminderAt) && !now.isAfter(occurrence.endAt)
    }

  private fun readExamJson(context: Context): String? {
    val runtimeFile = File(context.filesDir, EXAM_JSON_FILE)
    if (runtimeFile.exists() && runtimeFile.length() > 0L) {
      return runCatching { runtimeFile.readText(Charsets.UTF_8) }.getOrNull()
    }
    return runCatching {
      context.assets.open(EXAM_JSON_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
  }

  private fun parseOccurrence(exam: ExamArrangement): ExamOccurrence? {
    val match = examTimeRegex.find(exam.examTime.trim()) ?: return null
    val date = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
    val startTime = runCatching { LocalTime.parse(match.groupValues[2]) }.getOrNull() ?: return null
    val endTime = runCatching { LocalTime.parse(match.groupValues[3]) }.getOrNull() ?: return null
    return ExamOccurrence(exam, date, startTime, endTime)
  }
}
