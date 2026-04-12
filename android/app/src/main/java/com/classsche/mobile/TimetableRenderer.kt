package com.classsche.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

object TimetableRenderer {
  private val coursesPattern = Regex("""const courses = \[.*?];""", setOf(RegexOption.DOT_MATCHES_ALL))

  fun emptyHomeHtml(context: Context): String {
    return renderAsset(context, "home-view.html", emptyList())
      ?: fallbackHtml("当前设备还没有首页缓存，请先导出首页资源。")
  }

  fun emptyHtml(context: Context): String {
    return renderAsset(context, "timetable-view.html", emptyList())
      ?: fallbackHtml("当前设备还没有本地课表，请先登录并打开一次课表页。")
  }

  fun toHomeHtml(context: Context, courses: List<TimetableCourse>): String {
    return renderAsset(context, "home-view.html", courses)
      ?: fallbackHtml("首页缓存已更新，共解析 ${courses.size} 条课程。")
  }

  fun toHtml(context: Context, courses: List<TimetableCourse>): String {
    return renderAsset(context, "timetable-view.html", courses)
      ?: fallbackHtml("本地课表已更新，共解析 ${courses.size} 条课程。")
  }

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
          put("courseType", course.courseType)
          put("rawText", course.rawText)
        }
      )
    }
    return array.toString(2)
  }

  private fun renderAsset(context: Context, fileName: String, courses: List<TimetableCourse>): String? {
    val template = readAssetText(context, fileName) ?: return null
    return coursesPattern.replace(template, "const courses = ${toJson(courses)};")
  }

  private fun readAssetText(context: Context, fileName: String): String? {
    return try {
      context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: Exception) {
      null
    }
  }

  private fun fallbackHtml(message: String): String {
    val updatedAt = LocalDateTime.now().toString().replace('T', ' ')
    val escapedMessage = escape(message)
    return """
      <!DOCTYPE html>
      <html lang="zh-CN">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <title>本地课表</title>
        <style>
          body {
            margin: 0;
            min-height: 100vh;
            display: grid;
            place-items: center;
            padding: 24px;
            background: linear-gradient(180deg, #84b5ea 0, #78afe8 180px, #ffffff 180px, #ffffff 100%);
            color: #4e6786;
            font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
          }
          .wrap {
            width: min(520px, 100%);
            text-align: center;
          }
          h1 {
            margin: 0 0 8px;
            font-size: 28px;
            color: #ffffff;
          }
          .meta {
            margin: 0 0 16px;
            color: #f5f9ff;
            font-size: 12px;
          }
          .card {
            background: rgba(255,255,255,0.96);
            border-radius: 22px;
            box-shadow: 0 18px 44px rgba(68, 89, 129, 0.14);
            padding: 28px 24px;
          }
          h2 {
            margin: 0 0 10px;
            font-size: 22px;
            color: #4c6d95;
          }
          p {
            margin: 0;
            line-height: 1.7;
            font-size: 14px;
            color: #6d84a2;
          }
        </style>
      </head>
      <body>
        <div class="wrap">
          <h1>本地课表</h1>
          <p class="meta">更新时间：${escape(updatedAt)}</p>
          <div class="card">
            <h2>暂时无法渲染正式课表页</h2>
            <p>$escapedMessage</p>
          </div>
        </div>
      </body>
      </html>
    """.trimIndent()
  }

  private fun escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
}
