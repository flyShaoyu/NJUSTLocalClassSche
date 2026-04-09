package com.classsche.mobile

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private data class GridMeeting(
  val courseName: String,
  val teacher: String,
  val classroom: String,
  val weeks: String,
  val weekday: String,
  val rawText: String
)

object TimetableParser {
  fun parse(html: String): List<TimetableCourse> {
    val document = Jsoup.parse(html)
    val gridMeetings = parseGridMeetings(document)
    return parseDetailMeetings(document, gridMeetings)
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

  private fun splitHtmlLines(html: String): List<String> =
    normalizeText(
      html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(div|p|li|tr|font)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
    ).split("\n").map { it.trim() }.filter { it.isNotBlank() }

  private fun splitSegments(lines: List<String>): List<List<String>> {
    val segments = mutableListOf<MutableList<String>>()
    var current = mutableListOf<String>()

    for (line in lines) {
      if (line.matches(Regex("-{5,}"))) {
        if (current.isNotEmpty()) {
          segments += current
          current = mutableListOf()
        }
        continue
      }

      current += line
    }

    if (current.isNotEmpty()) {
      segments += current
    }

    return segments
  }

  private fun buildKey(courseName: String, teacher: String, classroom: String, weekday: String): String =
    listOf(courseName, teacher, classroom, weekday).joinToString("||") { cleanInlineText(it) }

  private fun fallbackKey(courseName: String, teacher: String, weekday: String): String =
    listOf(courseName, teacher, weekday).joinToString("||") { cleanInlineText(it) }

  private fun parseGridMeetings(document: Document): List<GridMeeting> {
    val table = document.selectFirst("#kbtable") ?: return emptyList()
    val rows = table.select("tr")
    if (rows.isEmpty()) return emptyList()

    val weekdayHeaders = rows.first()
      ?.select("th")
      ?.drop(1)
      ?.map { cleanInlineText(it.text()) }
      ?: emptyList()

    val meetings = mutableListOf<GridMeeting>()

    for (row in rows.drop(1)) {
      val cells = row.children().filter { child ->
        val tag = child.tagName()
        tag == "th" || tag == "td"
      }
      if (cells.size <= 1) continue

      cells.drop(1).forEachIndexed { columnIndex, cell ->
        val weekday = weekdayHeaders.getOrNull(columnIndex) ?: "星期${columnIndex + 1}"
        cell.select("div.kbcontent").forEach { detailDiv ->
          val segments = splitSegments(splitHtmlLines(detailDiv.html()))
          segments.forEach { segment ->
            parseGridMeetingSegment(segment, weekday)?.let { meetings += it }
          }
        }
      }
    }

    return meetings
  }

  private fun parseGridMeetingSegment(segment: List<String>, weekday: String): GridMeeting? {
    if (segment.isEmpty()) return null

    val courseName = segment.firstOrNull().orEmpty()
    val weekIndex = segment.indexOfFirst { it.contains("周") }
    val weeks = if (weekIndex >= 0) segment.getOrNull(weekIndex).orEmpty() else ""
    val classroom = if (weekIndex >= 0 && weekIndex < segment.lastIndex) segment.lastOrNull().orEmpty() else ""

    var teacher = ""
    if (weekIndex > 1) {
      teacher = segment.getOrNull(weekIndex - 1).orEmpty()
    }

    if (weekIndex > 2 && classroom.isNotBlank() && classroom == teacher) {
      teacher = segment.getOrNull(weekIndex - 2).orEmpty()
    }

    val cleanCourseName = cleanInlineText(courseName)
    if (cleanCourseName.isBlank()) return null

    return GridMeeting(
      courseName = cleanCourseName,
      teacher = cleanInlineText(teacher),
      classroom = cleanInlineText(classroom),
      weeks = cleanInlineText(weeks),
      weekday = weekday,
      rawText = segment.joinToString("\n")
    )
  }

  private fun buildGridIndex(gridMeetings: List<GridMeeting>): MutableMap<String, ArrayDeque<GridMeeting>> {
    val index = mutableMapOf<String, ArrayDeque<GridMeeting>>()

    for (meeting in gridMeetings) {
      listOf(
        buildKey(meeting.courseName, meeting.teacher, meeting.classroom, meeting.weekday),
        fallbackKey(meeting.courseName, meeting.teacher, meeting.weekday)
      ).forEach { key ->
        index.getOrPut(key) { ArrayDeque() }.addLast(meeting)
      }
    }

    return index
  }

  private fun splitTimeEntries(html: String): List<Pair<String, String>> =
    splitHtmlLines(html).mapNotNull { line ->
      val match = Regex("^(星期[一二三四五六日天])\\((.+?)\\)$").find(line) ?: return@mapNotNull null
      match.groupValues[1] to match.groupValues[2]
    }

  private fun splitClassrooms(value: String, expectedCount: Int): List<String> {
    val classrooms = cleanInlineText(value)
      .split(Regex("\\s*,\\s*"))
      .map { it.trim() }
      .filter { it.isNotBlank() }

    if (classrooms.size == expectedCount) return classrooms
    if (classrooms.size == 1 && expectedCount > 1) return List(expectedCount) { classrooms.first() }
    return classrooms
  }

  private fun takeWeeks(
    index: MutableMap<String, ArrayDeque<GridMeeting>>,
    courseName: String,
    teacher: String,
    classroom: String,
    weekday: String
  ): GridMeeting? {
    val exact = buildKey(courseName, teacher, classroom, weekday)
    val loose = fallbackKey(courseName, teacher, weekday)

    index[exact]?.removeFirstOrNull()?.let { return it }
    index[loose]?.removeFirstOrNull()?.let { return it }
    return null
  }

  private fun parseDetailMeetings(document: Document, gridMeetings: List<GridMeeting>): List<TimetableCourse> {
    val index = buildGridIndex(gridMeetings)
    val meetings = mutableListOf<TimetableCourse>()

    document.select("#dataList tr").drop(1).forEach { row ->
      val cells = row.select("td")
      if (cells.size < 10) return@forEach

      val courseCode = cleanInlineText(cells[1].text())
      val courseSequence = cleanInlineText(cells[2].text())
      val courseName = cleanInlineText(cells[3].text())
      val teacher = cleanInlineText(cells[4].text())
      val timeEntries = splitTimeEntries(cells[5].html())
      val classrooms = splitClassrooms(cells[7].text(), timeEntries.size)

      timeEntries.forEachIndexed { indexInRow, entry ->
        val classroom = classrooms.getOrNull(indexInRow) ?: classrooms.firstOrNull().orEmpty()
        val matched = takeWeeks(index, courseName, teacher, classroom, entry.first)

        meetings += TimetableCourse(
          courseName = courseName,
          weekday = entry.first,
          periods = entry.second,
          classroom = classroom,
          weeks = matched?.weeks.orEmpty(),
          teacher = teacher,
          courseCode = courseCode,
          courseSequence = courseSequence,
          rawText = matched?.rawText ?: listOf(courseName, teacher, "${entry.first}(${entry.second})", classroom)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        )
      }
    }

    return meetings
  }
}
