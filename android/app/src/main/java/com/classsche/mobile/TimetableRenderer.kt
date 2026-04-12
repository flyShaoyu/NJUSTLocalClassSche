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
    return fallbackHtml("当前设备还没有本地课表，请先登录并打开一次课表页。")
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

  fun homeHtmlFromJson(context: Context, rawJson: String): String? {
    val courses = coursesFromJson(rawJson) ?: return null
    return toHomeHtml(context, courses)
  }

  private fun renderAsset(context: Context, fileName: String, courses: List<TimetableCourse>): String? {
    val template = readAssetText(context, fileName) ?: return null
    return coursesPattern.replace(template, "const courses = ${toJson(courses)};")
  }

  private fun coursesFromJson(rawJson: String): List<TimetableCourse>? {
    return try {
      val array = JSONArray(rawJson)
      buildList(array.length()) {
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
    } catch (_: Exception) {
      null
    }
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
          :root {
            --sky: #78afe8;
            --sky-deep: #6aa3df;
            --paper: #f4f7fb;
            --card: rgba(255,255,255,0.97);
            --ink: #60758f;
            --muted: #9aaabd;
            --accent: #d59762;
            --line: rgba(144, 166, 192, 0.22);
            --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
            --font-ui: "PingFang SC", "Microsoft YaHei", sans-serif;
          }

          * {
            box-sizing: border-box;
          }

          html,
          body {
            margin: 0;
            min-height: 100vh;
            background: linear-gradient(180deg, #8bb8eb 0, var(--sky) 132px, var(--paper) 132px, var(--paper) 100%);
            color: var(--ink);
            font-family: var(--font-ui);
          }

          .wrap {
            width: min(620px, 100%);
            margin: 0 auto;
            padding: 18px 14px 20px;
          }

          .hero {
            color: #ffffff;
            text-align: center;
            padding: 10px 0 18px;
          }

          .hero h1 {
            margin: 0;
            font-size: 28px;
            font-family: var(--font-cn);
            letter-spacing: 2px;
          }

          .hero p {
            margin: 8px 0 0;
            color: rgba(245, 249, 255, 0.92);
            font-size: 12px;
          }

          .card {
            background: var(--card);
            border-radius: 18px;
            border: 1px solid rgba(187, 200, 217, 0.3);
            box-shadow: 0 14px 34px rgba(78, 101, 136, 0.1);
            overflow: hidden;
          }

          .head {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 16px 18px;
            border-bottom: 1px solid var(--line);
            color: var(--accent);
            font-family: var(--font-cn);
            font-size: 18px;
          }

          .icon {
            width: 26px;
            height: 26px;
            border-radius: 50%;
            display: grid;
            place-items: center;
            background: rgba(213, 151, 98, 0.14);
            font-size: 14px;
          }

          .body {
            padding: 22px 18px 20px;
          }

          .body h2 {
            margin: 0 0 8px;
            font-size: 20px;
            color: #4f698c;
            font-family: var(--font-cn);
          }

          .body p {
            margin: 0;
            line-height: 1.7;
            font-size: 14px;
            color: #6f829a;
          }

          .tip {
            margin-top: 14px;
            padding: 12px 14px;
            border-radius: 12px;
            background: rgba(240, 246, 253, 0.95);
            color: var(--muted);
            font-size: 13px;
          }

          @media (max-width: 480px) {
            .wrap {
              padding: 12px 10px 16px;
            }

            .hero h1 {
              font-size: 24px;
            }

            .head {
              padding: 14px 16px;
              font-size: 16px;
            }

            .body {
              padding: 18px 16px 16px;
            }
          }
        </style>
      </head>
      <body>
        <div class="wrap">
          <div class="hero">
            <h1>课表查询</h1>
            <p>更新时间：${escape(updatedAt)}</p>
          </div>
          <div class="card">
            <div class="head">
              <span class="icon">!</span>
              <span>暂无本地课表缓存</span>
            </div>
            <div class="body">
              <h2>还没有可用课表</h2>
              <p>$escapedMessage</p>
              <div class="tip">先到“个人中心”登录，再打开一次课表页，系统就会自动生成本地缓存。</div>
            </div>
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
