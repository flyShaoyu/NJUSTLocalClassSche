package com.classsche.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

object TimetableRenderer {
  private val weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

  fun toJson(courses: List<TimetableCourse>): String {
    val array = JSONArray()
    courses.forEach { course ->
      array.put(
        JSONObject().apply {
          put("courseName", course.courseName)
          put("weekday", course.weekday)
          put("periods", course.periods)
          put("classroom", course.classroom)
          put("weeks", course.weeks)
          put("teacher", course.teacher)
          put("courseCode", course.courseCode)
          put("courseSequence", course.courseSequence)
          put("rawText", course.rawText)
        }
      )
    }
    return array.toString(2)
  }

  fun toHtml(courses: List<TimetableCourse>): String {
    val grouped = weekdays.associateWith { weekday ->
      courses.filter { it.weekday == weekday }.sortedBy { extractStartPeriod(it.periods) }
    }

    val sections = weekdays.joinToString("") { weekday ->
      val items = grouped[weekday].orEmpty()
      val cards = if (items.isEmpty()) {
        """<div class="empty-card">今天没有课程</div>"""
      } else {
        items.joinToString("") { course ->
          """
          <article class="course-card">
            <div class="course-head">
              <div class="course-name">${escape(course.courseName)}</div>
              <div class="course-period">${escape(course.periods)}</div>
            </div>
            <div class="course-meta">${escape(course.classroom.ifBlank { "待定地点" })}</div>
            <div class="course-meta">${escape(course.teacher.ifBlank { "未标注教师" })}</div>
            <div class="course-meta">${escape(course.weeks.ifBlank { "周次未识别" })}</div>
          </article>
          """.trimIndent()
        }
      }

      """
      <section class="day-section">
        <div class="day-title">${escape(weekday)}</div>
        <div class="day-cards">$cards</div>
      </section>
      """.trimIndent()
    }

    return """
      <!DOCTYPE html>
      <html lang="zh-CN">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <title>课表缓存</title>
        <style>
          :root {
            --sky-top: #6ca7e0;
            --sky-bottom: #79afe6;
            --paper: #f7f8fc;
            --ink: #42617f;
            --muted: #7590ad;
            --line: rgba(103, 137, 178, 0.12);
          }
          * { box-sizing: border-box; }
          body {
            margin: 0;
            min-height: 100vh;
            font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
            color: var(--ink);
            background:
              radial-gradient(circle at top left, rgba(255,255,255,0.42), transparent 18%),
              linear-gradient(180deg, #8ebcf0 0, #7fb2ea 170px, var(--paper) 170px, var(--paper) 100%);
          }
          .app {
            max-width: 760px;
            margin: 0 auto;
            padding: 20px 12px 24px;
          }
          .hero {
            color: white;
            text-align: center;
            padding: 8px 0 18px;
          }
          .hero h1 {
            margin: 0;
            font-size: 24px;
            letter-spacing: 2px;
          }
          .hero p {
            margin: 8px 0 0;
            font-size: 12px;
            opacity: 0.92;
          }
          .board {
            background: rgba(255,255,255,0.94);
            border-radius: 20px;
            box-shadow: 0 18px 42px rgba(70, 94, 138, 0.12);
            overflow: hidden;
          }
          .day-section + .day-section {
            border-top: 1px solid var(--line);
          }
          .day-title {
            position: sticky;
            top: 0;
            z-index: 1;
            padding: 12px 14px;
            background: linear-gradient(180deg, rgba(232,243,255,0.96), rgba(255,255,255,0.94));
            color: #4d6f97;
            font-size: 14px;
            font-weight: 700;
          }
          .day-cards {
            padding: 10px;
            display: grid;
            gap: 10px;
          }
          .course-card {
            border-radius: 16px;
            padding: 12px;
            background: linear-gradient(180deg, #7cb2ea, #5f95dc);
            color: white;
            box-shadow: 0 8px 18px rgba(75, 106, 162, 0.15);
          }
          .course-head {
            display: flex;
            justify-content: space-between;
            gap: 8px;
            align-items: flex-start;
          }
          .course-name {
            flex: 1;
            min-width: 0;
            font-size: 15px;
            font-weight: 700;
            line-height: 1.35;
            word-break: break-word;
          }
          .course-period {
            white-space: nowrap;
            font-size: 11px;
            opacity: 0.95;
          }
          .course-meta {
            margin-top: 6px;
            font-size: 12px;
            line-height: 1.45;
            opacity: 0.96;
            word-break: break-word;
          }
          .empty-card {
            padding: 16px 14px;
            border-radius: 14px;
            background: linear-gradient(180deg, rgba(245,249,255,0.98), rgba(236,242,250,0.98));
            color: var(--muted);
            text-align: center;
            font-size: 13px;
          }
        </style>
      </head>
      <body>
        <div class="app">
          <div class="hero">
            <h1>课表缓存</h1>
            <p>本地更新时间：${escape(LocalDateTime.now().toString().replace('T', ' '))}</p>
          </div>
          <main class="board">
            $sections
          </main>
        </div>
      </body>
      </html>
    """.trimIndent()
  }

  private fun extractStartPeriod(periods: String): Int =
    Regex("(\\d+)").find(periods)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 999

  private fun escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
}
