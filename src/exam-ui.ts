import { ExamArrangement } from "./types.js";

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");

const buildExamPageScript = (examsJson: string): string => `
  <script>
    const exams = ${examsJson};
    const weekLabels = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
    const list = document.getElementById("examList");
    const summary = document.getElementById("summaryText");

    const parseExamTime = (value) => {
      const match = String(value || "").match(/^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})~(\\d{2}:\\d{2})/);
      if (!match) return { start: null, end: null };
      return {
        start: new Date(match[1] + "T" + match[2] + ":00"),
        end: new Date(match[1] + "T" + match[3] + ":00")
      };
    };

    const escapeHtml = (value) => String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");

    const dayDistance = (date) => {
      if (!(date instanceof Date) || Number.isNaN(date.valueOf())) return null;
      const now = new Date();
      const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const target = new Date(date.getFullYear(), date.getMonth(), date.getDate());
      return Math.round((target - today) / 86400000);
    };

    const isPast = (exam) => {
      const time = parseExamTime(exam.examTime);
      return time.end ? time.end < new Date() : false;
    };

    const distanceLabel = (exam) => {
      const time = parseExamTime(exam.examTime);
      const diff = dayDistance(time.start);
      if (diff === null) return "待确认";
      if (isPast(exam)) return "已结束";
      if (diff === 0) return "今天";
      if (diff === 1) return "明天";
      if (diff > 1) return diff + " 天";
      return "已结束";
    };

    const titlePrefix = (exam) => {
      const time = parseExamTime(exam.examTime);
      return time.start ? "[" + weekLabels[time.start.getDay()] + "]\u00A0" : "";
    };

    const compactLine = (exam) => {
      const room = exam.examRoom ? " @" + exam.examRoom : "";
      const seat = exam.seatNumber ? " " + exam.seatNumber : "";
      return exam.examTime + room + seat;
    };

    const detailRows = (exam) => {
      const room = exam.examRoom || "待确认";
      const seat = exam.seatNumber || "待确认";
      return [
        ["地点 / 座位号", room + " / " + seat],
        ["任课老师", exam.teacher || "未标注"],
        ["距离考试", distanceLabel(exam)]
      ];
    };

    const firstActiveIndex = () => {
      const next = exams.findIndex((exam) => !isPast(exam));
      return next >= 0 ? next : Math.max(0, exams.length - 1);
    };

    const initialRender = () => {
      const done = exams.filter(isPast).length;
      if (summary) {
        summary.textContent = exams.length ? "共 " + exams.length + " 场，已结束 " + done + " 场" : "暂无考试安排";
      }

      if (!list) return;
      if (!exams.length) {
        list.innerHTML = '<section class="empty-card">暂无考试安排</section>';
        return;
      }

      const activeIndex = firstActiveIndex();
      list.innerHTML = exams.map((exam, index) => {
        const active = index === activeIndex;
        const past = isPast(exam);
        const rows = detailRows(exam).map(([label, value]) =>
          '<div class="detail-row"><span>' + escapeHtml(label) + '</span><strong>' + escapeHtml(value) + '</strong></div>'
        ).join("");

        return '<article class="exam-card' + (active ? " active" : "") + (past ? " past" : "") + '" data-index="' + index + '">' +
          '<button class="card-head" type="button" aria-expanded="' + active + '">' +
            '<span class="card-title">' + escapeHtml(titlePrefix(exam) + exam.courseName) + '</span>' +
            '<span class="chevron" aria-hidden="true"></span>' +
            '<span class="card-line">' + escapeHtml(active ? exam.examTime : compactLine(exam)) + '</span>' +
          '</button>' +
          '<div class="card-detail">' + rows + '</div>' +
        '</article>';
      }).join("");

      list.querySelectorAll(".card-head").forEach((button) => {
        button.addEventListener("click", () => {
          const card = button.closest(".exam-card");
          if (!card) return;

          const index = Number(card.getAttribute("data-index"));
          const exam = exams[index];
          const line = card.querySelector(".card-line");

          const isActive = card.classList.toggle("active");
          button.setAttribute("aria-expanded", isActive);

          if (line && exam) {
            line.textContent = isActive ? exam.examTime : compactLine(exam);
          }
        });
      });
    };

    initialRender();
  </script>
`;

export const renderExamPage = (exams: ExamArrangement[], semester = "2025-2026-2"): string => `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <title>考试安排</title>
  <style>
    :root {
      --sky: #72a9e2;
      --paper: #f6f6f6;
      --card: #ffffff;
      --ink: #5d636b;
      --muted: #9ca1a9;
      --accent: #69a9df;
      --line: rgba(128, 136, 148, 0.14);
      --font-ui: sans-serif;
    }

    * { box-sizing: border-box; }

    html,
    body {
      margin: 0;
      min-height: 100%;
      background: var(--paper);
      color: var(--ink);
      font-family: var(--font-ui);
    }

    body { overflow-x: hidden; }

    .app {
      max-width: 780px;
      min-height: 100vh;
      margin: 0 auto;
      background: var(--paper);
    }

    main {
      padding: 34px clamp(18px, 4vw, 34px) 42px;
    }

    .hero {
      min-height: 112px;
      padding: 0 12px 6px;
    }

    .hero h1 {
      margin: 0 0 22px;
      font-size: clamp(31px, 7.5vw, 51px);
      line-height: 0.95;
      color: #202329;
      font-weight: 700;
      letter-spacing: 0;
    }

    .hero p {
      margin: 10px 0;
      font-size: clamp(17px, 3.9vw, 24px);
      line-height: 1.1;
      color: #74777d;
      font-weight: 600;
    }

    .hero .term {
      color: #8d9096;
      font-weight: 500;
      padding-left: 8px;
    }

    .summary {
      margin-top: 2px;
      margin-bottom: 0;
      color: #a4a7ae;
      font-size: 12px;
      font-weight: 600;
    }

    .exam-list {
      display: grid;
      gap: 10px;
    }

    .exam-card {
      background: var(--card);
      border: 1px solid rgba(255,255,255,0.82);
      border-radius: 7px;
      box-shadow: 0 10px 22px rgba(110, 120, 135, 0.05);
      overflow: hidden;
    }

    .card-head {
      width: 100%;
      min-height: 70px;
      padding: 20px 18px 15px;
      border: 0;
      background: transparent;
      color: inherit;
      display: grid;
      grid-template-columns: minmax(0, 1fr) 14px;
      gap: 4px 8px;
      text-align: left;
    }

    .card-title {
      min-width: 0;
      color: #80838a;
      font-size: clamp(18px, 4.5vw, 25px);
      font-weight: 550;
      line-height: 1.3;
      word-break: keep-all;
      overflow-wrap: anywhere;
    }

    .chevron {
      width: 10px;
      height: 10px;
      align-self: center;
      justify-self: center;
      border-right: 3px solid #d1d4d9;
      border-bottom: 3px solid #d1d4d9;
      transform: rotate(45deg);
      transition: transform 160ms ease;
    }

    .card-line {
      grid-column: 1 / -1;
      margin-top: 6px;
      color: #a4a7ae;
      font-size: clamp(12px, 3.075vw, 18px);
      line-height: 1.5;
      white-space: normal;
    }

    .exam-card.past .card-title {
      color: #74777d;
      text-decoration: line-through;
      text-decoration-thickness: 2px;
    }

    .exam-card.past .card-line {
      color: #a9abb1;
    }

    .exam-card.active .card-title {
      color: var(--accent);
    }

    .exam-card.active .chevron {
      transform: rotate(225deg);
      border-color: #c9ccd2;
    }

    .card-detail {
      display: none;
      padding: 0 18px 20px;
    }

    .exam-card.active .card-detail {
      display: block;
    }

    .detail-row {
      min-height: 58px;
      border-top: 1px solid var(--line);
      display: grid;
      grid-template-columns: minmax(86px, 28%) minmax(0, 1fr);
      align-items: center;
      gap: 8px;
      font-size: clamp(14px, 3.375vw, 20px);
    }

    .detail-row span {
      font-weight: 700;
      color: #777b83;
      line-height: 1.5;
    }

    .detail-row strong {
      min-width: 0;
      color: #656a72;
      font-weight: 500;
      line-height: 1.5;
      overflow-wrap: anywhere;
    }

    .empty-card {
      min-height: 160px;
      display: grid;
      place-items: center;
      background: #fff;
      border-radius: 7px;
      color: var(--muted);
      font-size: 20px;
    }

    @media (max-width: 430px) {
      main {
        padding-left: 16px;
        padding-right: 16px;
      }

      .card-head {
        padding-left: 16px;
        padding-right: 16px;
      }

      .card-detail {
        padding-left: 16px;
        padding-right: 16px;
      }
    }
  </style>
</head>
<body>
  <div class="app">
    <main>
      <section class="hero" aria-labelledby="pageTitle">
        <h1 id="pageTitle">考试安排</h1>
        <p>学期：<span class="term">${semester}</span></p>
        <p>祝君考试顺利！</p>
        <p>数据同步可能出错，请以网站为准！</p>
        <div id="summaryText" class="summary"></div>
      </section>
      <section id="examList" class="exam-list" aria-label="考试安排列表"></section>
    </main>
  </div>
  ${buildExamPageScript(serializeForScript(exams))}
</body>
</html>`;
