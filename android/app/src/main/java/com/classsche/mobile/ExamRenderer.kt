package com.classsche.mobile

import org.json.JSONArray
import org.json.JSONObject

object ExamRenderer {
  fun toJson(exams: List<ExamArrangement>): String {
    val array = JSONArray()
    exams.forEach { exam ->
      array.put(
        JSONObject()
          .put("index", exam.index)
          .put("examSession", exam.examSession)
          .put("courseCode", exam.courseCode)
          .put("courseName", exam.courseName)
          .put("examTime", exam.examTime)
          .put("examRoom", exam.examRoom)
          .put("seatNumber", exam.seatNumber)
          .put("teacher", exam.teacher)
          .put("rawText", exam.rawText)
      )
    }
    return array.toString(2)
  }

  fun emptyHtml(): String = toHtml(emptyList())

  fun toHtml(exams: List<ExamArrangement>): String {
    val examsJson = serializeForScript(toJson(exams))
    return """
      <!DOCTYPE html>
      <html lang="zh-CN">
      <head>
        <meta charset="UTF-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
        <title>&#32771;&#35797;&#23433;&#25490;</title>
        <style>
          :root {
            --bg: #f4f6f9;
            --card: #ffffff;
            --text: #243042;
            --muted: #738195;
            --accent: #5f91d2;
            --accent-soft: #e8f0fb;
            --line: #e3e8ef;
          }

          * { box-sizing: border-box; }

          body {
            margin: 0;
            background: linear-gradient(180deg, #edf4fb 0%, var(--bg) 180px);
            color: var(--text);
            font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
          }

          main {
            max-width: 860px;
            margin: 0 auto;
            padding: 24px 18px 40px;
          }

          .hero {
            padding: 8px 4px 22px;
          }

          .hero h1 {
            margin: 0;
            font-size: 34px;
            font-weight: 700;
            letter-spacing: 0.02em;
          }

          .hero p {
            margin: 10px 0 0;
            color: var(--muted);
            font-size: 15px;
            line-height: 1.6;
          }

          .summary {
            margin-top: 10px;
            display: inline-flex;
            align-items: center;
            gap: 8px;
            padding: 8px 12px;
            border-radius: 999px;
            background: rgba(255, 255, 255, 0.75);
            border: 1px solid rgba(95, 145, 210, 0.14);
            color: #5678ab;
            font-size: 13px;
          }

          .list {
            display: grid;
            gap: 14px;
          }

          .card {
            background: var(--card);
            border: 1px solid var(--line);
            border-radius: 20px;
            padding: 18px 18px 16px;
            box-shadow: 0 12px 28px rgba(67, 92, 124, 0.08);
          }

          .card.upcoming {
            border-color: rgba(95, 145, 210, 0.26);
            background: linear-gradient(180deg, #ffffff 0%, #f8fbff 100%);
          }

          .card.past {
            opacity: 0.78;
          }

          .eyebrow {
            display: inline-flex;
            align-items: center;
            padding: 4px 10px;
            border-radius: 999px;
            background: var(--accent-soft);
            color: var(--accent);
            font-size: 12px;
            font-weight: 600;
          }

          .card.past .eyebrow {
            background: #eef1f5;
            color: #7f8a98;
          }

          .title {
            margin: 12px 0 8px;
            font-size: 21px;
            font-weight: 700;
            line-height: 1.35;
          }

          .meta {
            margin: 0;
            color: var(--muted);
            font-size: 14px;
            line-height: 1.7;
          }

          .grid {
            display: grid;
            grid-template-columns: repeat(2, minmax(0, 1fr));
            gap: 10px;
            margin-top: 14px;
          }

          .item {
            padding: 12px 13px;
            border-radius: 14px;
            background: #f8fafc;
            border: 1px solid #edf1f5;
          }

          .item span {
            display: block;
            color: var(--muted);
            font-size: 12px;
            margin-bottom: 6px;
          }

          .item strong {
            display: block;
            font-size: 14px;
            font-weight: 600;
            line-height: 1.55;
            word-break: break-word;
          }

          .empty {
            padding: 38px 18px;
            border-radius: 20px;
            text-align: center;
            color: var(--muted);
            background: rgba(255, 255, 255, 0.75);
            border: 1px dashed rgba(115, 129, 149, 0.28);
          }

          @media (max-width: 640px) {
            main {
              padding: 20px 14px 30px;
            }

            .hero h1 {
              font-size: 28px;
            }

            .grid {
              grid-template-columns: 1fr;
            }
          }
        </style>
      </head>
      <body>
        <main>
          <section class="hero">
            <h1>&#32771;&#35797;&#23433;&#25490;</h1>
            <p>&#27599;&#27425;&#30331;&#24405;&#25110;&#21047;&#26032;&#21518;&#65292;&#36825;&#37324;&#30340;&#20869;&#23481;&#37117;&#20250;&#25353;&#26368;&#26032;&#25945;&#21153;&#25968;&#25454;&#37325;&#26032;&#21516;&#27493;&#12290;</p>
            <div class="summary" id="summary">&#21152;&#36733;&#20013;</div>
          </section>
          <section class="list" id="list" aria-label="exam-list"></section>
        </main>
        <script>
          const exams = JSON.parse($examsJson);
          const list = document.getElementById("list");
          const summary = document.getElementById("summary");
          const copy = {
            empty: "\\u6682\\u65e0\\u8003\\u8bd5\\u5b89\\u6392",
            ended: "\\u5df2\\u7ed3\\u675f",
            pending: "\\u5f85\\u786e\\u8ba4",
            today: "\\u4eca\\u5929",
            tomorrow: "\\u660e\\u5929",
            daysLater: "\\u5929\\u540e",
            unmatchedTeacher: "\\u672a\\u5339\\u914d",
            unnamedExam: "\\u672a\\u547d\\u540d\\u8003\\u8bd5",
            timePending: "\\u65f6\\u95f4\\u5f85\\u786e\\u8ba4",
            room: "\\u5730\\u70b9",
            seat: "\\u5ea7\\u4f4d\\u53f7",
            teacher: "\\u4efb\\u8bfe\\u8001\\u5e08",
            session: "\\u8003\\u8bd5\\u573a\\u6b21",
            code: "\\u8bfe\\u7a0b\\u4ee3\\u7801",
            status: "\\u72b6\\u6001",
            totalPrefix: "\\u5171 ",
            totalMiddle: " \\u573a\\uff0c\\u5df2\\u7ed3\\u675f ",
            totalSuffix: " \\u573a"
          };

          const escapeHtml = (value) => String(value ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");

          const parseExamTime = (value) => {
            const match = String(value || "").match(/^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})~(\\d{2}:\\d{2})/);
            if (!match) return { start: null, end: null };
            return {
              start: new Date(match[1] + "T" + match[2] + ":00"),
              end: new Date(match[1] + "T" + match[3] + ":00")
            };
          };

          const isPast = (exam) => {
            const time = parseExamTime(exam.examTime);
            return time.end ? time.end < new Date() : false;
          };

          const dayDistance = (exam) => {
            const time = parseExamTime(exam.examTime);
            if (!time.start) return null;
            const now = new Date();
            const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
            const target = new Date(time.start.getFullYear(), time.start.getMonth(), time.start.getDate());
            return Math.round((target - today) / 86400000);
          };

          const distanceLabel = (exam) => {
            if (isPast(exam)) return copy.ended;
            const diff = dayDistance(exam);
            if (diff === null) return copy.pending;
            if (diff === 0) return copy.today;
            if (diff === 1) return copy.tomorrow;
            if (diff > 1) return diff + " " + copy.daysLater;
            return copy.pending;
          };

          const firstUpcomingIndex = exams.findIndex((exam) => !isPast(exam));
          const highlightIndex = firstUpcomingIndex >= 0 ? firstUpcomingIndex : -1;
          const finishedCount = exams.filter(isPast).length;

          summary.textContent = exams.length
            ? copy.totalPrefix + exams.length + copy.totalMiddle + finishedCount + copy.totalSuffix
            : copy.empty;

          if (!exams.length) {
            list.innerHTML = '<section class="empty">' + copy.empty + "</section>";
          } else {
            list.innerHTML = exams.map((exam, index) => {
              const cardClasses = [
                "card",
                isPast(exam) ? "past" : "",
                index === highlightIndex ? "upcoming" : ""
              ].filter(Boolean).join(" ");
              const room = exam.examRoom || copy.pending;
              const seat = exam.seatNumber || copy.pending;
              const teacher = exam.teacher || copy.unmatchedTeacher;
              const session = exam.examSession || copy.pending;
              const code = exam.courseCode || copy.pending;
              return '<article class="' + cardClasses + '">' +
                '<div class="eyebrow">' + escapeHtml(distanceLabel(exam)) + '</div>' +
                '<h2 class="title">' + escapeHtml(exam.courseName || copy.unnamedExam) + '</h2>' +
                '<p class="meta">' + escapeHtml(exam.examTime || copy.timePending) + '</p>' +
                '<div class="grid">' +
                  '<div class="item"><span>' + copy.room + '</span><strong>' + escapeHtml(room) + '</strong></div>' +
                  '<div class="item"><span>' + copy.seat + '</span><strong>' + escapeHtml(seat) + '</strong></div>' +
                  '<div class="item"><span>' + copy.teacher + '</span><strong>' + escapeHtml(teacher) + '</strong></div>' +
                  '<div class="item"><span>' + copy.session + '</span><strong>' + escapeHtml(session) + '</strong></div>' +
                  '<div class="item"><span>' + copy.code + '</span><strong>' + escapeHtml(code) + '</strong></div>' +
                  '<div class="item"><span>' + copy.status + '</span><strong>' + escapeHtml(distanceLabel(exam)) + '</strong></div>' +
                '</div>' +
              '</article>';
            }).join("");
          }
        </script>
      </body>
      </html>
    """.trimIndent()
  }

  private fun serializeForScript(value: String): String =
    JSONObject.quote(value)
      .replace("<", "\\u003c")
      .replace(">", "\\u003e")
      .replace("&", "\\u0026")
      .replace("\u2028", "\\u2028")
      .replace("\u2029", "\\u2029")
}
