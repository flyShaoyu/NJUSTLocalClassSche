package com.classsche.mobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream

object ExamParser {
  fun parse(html: String, courses: List<TimetableCourse> = emptyList()): List<ExamArrangement> =
    parseDocument(Jsoup.parse(html), courses)

  fun parseBytes(bytes: ByteArray, baseUri: String, courses: List<TimetableCourse> = emptyList()): List<ExamArrangement> =
    parseDocument(Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri), courses)

  private fun parseDocument(document: Document, courses: List<TimetableCourse>): List<ExamArrangement> {
    val teacherIndex = buildTeacherIndex(courses)
    val exams = mutableListOf<ExamArrangement>()

    val rows = document.select("#dataList tr")
    if (rows.isEmpty()) {
      val title = document.title()
      if (title.contains("登录") || title.contains("login", ignoreCase = true)) {
        throw IllegalStateException("未获取到考试列表，会话可能已过期。当前页面: $title")
      }
      throw IllegalStateException("未在考试页面中找到 #dataList 表格。当前页面: $title")
    }

    rows.drop(1).forEach { row ->
      val cells = row.select("td")
      if (cells.size < 7) return@forEach

      val indexText = cleanInlineText(cells[0].text())
      val examSession = cleanInlineText(cells[1].text())
      val courseCode = cleanInlineText(cells[2].text())
      val courseName = cleanInlineText(cells[3].text())
      val examTime = cleanInlineText(cells[4].text())
      val examRoom = cleanInlineText(cells[5].text())
      val seatNumber = cleanInlineText(cells[6].text())
      val teacher = findTeacher(teacherIndex, courseCode, courseName)

      if (courseCode.isBlank() && courseName.isBlank() && examTime.isBlank()) {
        return@forEach
      }

      exams += ExamArrangement(
        index = indexText.toIntOrNull() ?: (exams.size + 1),
        examSession = examSession,
        courseCode = courseCode,
        courseName = courseName,
        examTime = examTime,
        examRoom = examRoom,
        seatNumber = seatNumber,
        teacher = teacher,
        rawText = listOf(
          indexText,
          examSession,
          courseCode,
          courseName,
          teacher,
          examTime,
          examRoom,
          seatNumber
        ).filter { it.isNotBlank() }.joinToString("\n")
      )
    }

    return exams
  }

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

  private fun normalizeLookupKey(value: String): String =
    cleanInlineText(value).replace(Regex("\\s+"), "").lowercase()

  private fun addTeacher(index: MutableMap<String, MutableSet<String>>, key: String, teacher: String) {
    val normalizedKey = normalizeLookupKey(key)
    val normalizedTeacher = cleanInlineText(teacher)
    if (normalizedKey.isBlank() || normalizedTeacher.isBlank()) {
      return
    }

    index.getOrPut(normalizedKey) { linkedSetOf() }.add(normalizedTeacher)
  }

  private fun buildTeacherIndex(courses: List<TimetableCourse>): Map<String, Set<String>> {
    val index = linkedMapOf<String, MutableSet<String>>()
    courses.forEach { course ->
      addTeacher(index, course.courseCode, course.teacher)
      addTeacher(index, course.courseName, course.teacher)
    }
    return index
  }

  private fun findTeacher(teacherIndex: Map<String, Set<String>>, courseCode: String, courseName: String): String {
    val teachers =
      teacherIndex[normalizeLookupKey(courseCode)]
        ?: teacherIndex[normalizeLookupKey(courseName)]
        ?: return ""

    return teachers.joinToString(" / ")
  }
}
