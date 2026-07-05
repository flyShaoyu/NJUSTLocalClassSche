package com.classsche.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object TimetableSemesterStore {
  private const val PREFS_NAME = "classsche_prefs"
  private const val PREF_SELECTED_SEMESTER = "selected_timetable_semester"
  private const val PREF_REFRESH_REQUESTED = "selected_timetable_semester_refresh_requested"
  private const val CATALOG_FILE = "timetable-semesters.json"
  private const val RAW_HTML_FILE = "timetable.raw.html"

  data class SemesterCatalog(
    val availableSemesters: List<String>,
    val currentSemester: String,
    val selectedSemester: String,
    val updatedAt: Long
  )

  data class SemesterCalendar(
    val semester: String,
    val week1Monday: LocalDate?,
    val usesRealDates: Boolean
  )

  fun readCatalog(context: Context): SemesterCatalog {
    val selected = readSelectedSemester(context)
    val file = File(context.filesDir, CATALOG_FILE)
    if (!file.exists() || file.length() <= 0L) {
      return SemesterCatalog(
        availableSemesters = emptyList(),
        currentSemester = "",
        selectedSemester = selected,
        updatedAt = 0L
      )
    }

    val payload = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    if (payload == null) {
      return SemesterCatalog(
        availableSemesters = emptyList(),
        currentSemester = "",
        selectedSemester = selected,
        updatedAt = 0L
      )
    }

    val available = buildList {
      val array = payload.optJSONArray("availableSemesters") ?: JSONArray()
      for (index in 0 until array.length()) {
        val value = array.optString(index).trim()
        if (value.isNotBlank()) add(value)
      }
    }.distinct()

    return SemesterCatalog(
      availableSemesters = available,
      currentSemester = payload.optString("currentSemester").trim(),
      selectedSemester = selected.ifBlank { payload.optString("currentSemester").trim() },
      updatedAt = payload.optLong("updatedAt")
    )
  }

  fun saveSelectedSemester(context: Context, semester: String) {
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(PREF_SELECTED_SEMESTER, semester.trim())
      .apply()
  }

  fun readSelectedSemester(context: Context): String {
    return context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(PREF_SELECTED_SEMESTER, "")
      .orEmpty()
      .trim()
  }

  fun requestRefresh(context: Context) {
    context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putBoolean(PREF_REFRESH_REQUESTED, true)
      .apply()
  }

  fun consumeRefreshRequest(context: Context): Boolean {
    val prefs = context.applicationContext
      .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val requested = prefs.getBoolean(PREF_REFRESH_REQUESTED, false)
    if (requested) {
      prefs.edit().putBoolean(PREF_REFRESH_REQUESTED, false).apply()
    }
    return requested
  }

  fun resolveDesiredSemester(context: Context): String {
    val catalog = readCatalog(context)
    return catalog.selectedSemester
      .ifBlank { catalog.currentSemester }
      .ifBlank { catalog.availableSemesters.firstOrNull().orEmpty() }
  }

  fun resolveRenderedSemester(context: Context): String {
    val catalog = readCatalog(context)
    return catalog.currentSemester
      .ifBlank { catalog.selectedSemester }
  }

  fun updateCatalog(
    context: Context,
    availableSemesters: List<String>,
    currentSemester: String
  ): SemesterCatalog {
    val normalizedAvailable = availableSemesters
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .distinct()
    val normalizedCurrent = currentSemester.trim()
      .ifBlank { normalizedAvailable.firstOrNull().orEmpty() }
    val catalog = SemesterCatalog(
      availableSemesters = normalizedAvailable,
      currentSemester = normalizedCurrent,
      selectedSemester = readSelectedSemester(context).ifBlank { normalizedCurrent },
      updatedAt = System.currentTimeMillis()
    )
    writeCatalog(context, catalog)
    return catalog
  }

  fun updateFromTimetableHtml(context: Context, html: String): SemesterCatalog {
    val document = Jsoup.parse(html)
    val select = document.selectFirst("select[name=xnxq01id]") ?: document.selectFirst("#xnxq01id")
    val available = buildList {
      select?.select("option")?.forEach { option ->
        val value = option.attr("value").trim().ifBlank { option.text().trim() }
        if (value.isNotBlank()) add(value)
      }
    }.distinct()
    val current = select?.selectFirst("option[selected]")?.attr("value")?.trim().orEmpty()
      .ifBlank { select?.`val`()?.trim().orEmpty() }
      .ifBlank { available.firstOrNull().orEmpty() }

    return updateCatalog(context, available, current)
  }

  fun refreshCatalogFromRawHtmlIfNeeded(context: Context) {
    val file = File(context.filesDir, RAW_HTML_FILE)
    if (!file.exists() || file.length() <= 0L) {
      return
    }
    val currentCatalog = readCatalog(context)
    if (currentCatalog.availableSemesters.isNotEmpty() && currentCatalog.currentSemester.isNotBlank()) {
      return
    }
    runCatching { updateFromTimetableHtml(context, file.readText(Charsets.UTF_8)) }
  }

  fun resolveCalendar(semester: String?): SemesterCalendar {
    val normalized = semester.orEmpty().trim()
    return when (normalized) {
      "2025-2026-2" -> SemesterCalendar(normalized, LocalDate.of(2026, 3, 2), true)
      "2025-2026-3",
      "2026-2027-1" -> SemesterCalendar(normalized, LocalDate.of(2026, 8, 24), true)
      else -> SemesterCalendar(normalized, null, false)
    }
  }

  fun weekForDate(calendar: SemesterCalendar, date: LocalDate): Int? {
    val start = calendar.week1Monday ?: return null
    val diffDays = ChronoUnit.DAYS.between(start, date).toInt()
    return 1 + Math.floorDiv(diffDays, 7)
  }

  fun shouldPreferFullTimetable(
    semester: String,
    allWeeks: List<Int>,
    today: LocalDate = LocalDate.now()
  ): Boolean {
    if (allWeeks.isEmpty()) return true
    val calendar = resolveCalendar(semester)
    val currentWeek = weekForDate(calendar, today) ?: return true
    val minWeek = allWeeks.minOrNull() ?: return true
    val maxWeek = allWeeks.maxOrNull() ?: return true
    return currentWeek < minWeek || currentWeek > maxWeek
  }

  private fun writeCatalog(context: Context, catalog: SemesterCatalog) {
    val file = File(context.filesDir, CATALOG_FILE)
    val payload = JSONObject().apply {
      put("currentSemester", catalog.currentSemester)
      put("updatedAt", catalog.updatedAt)
      put("availableSemesters", JSONArray().apply {
        catalog.availableSemesters.forEach(::put)
      })
    }
    file.writeText(payload.toString(), Charsets.UTF_8)
  }
}
