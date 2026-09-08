import { LevelExamRecord } from "./types.js";

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");

const buildLevelExamPageScript = (recordsJson: string): string => `
  <script>
    const levelExams = ${recordsJson};

    const escapeHtml = (value) => String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");

    const toNumber = (value) => {
      const parsed = Number(String(value ?? "").trim());
      return Number.isFinite(parsed) ? parsed : null;
    };

    const displayPart = (value) => {
      const text = String(value ?? "").trim();
      if (!text || text === "0") return "--";
      return text;
    };

    const mainScore = (record) => {
      const totalScore = String(record.totalScore ?? "").trim();
      if (totalScore && totalScore !== "0") return totalScore;
      const totalLevel = String(record.totalLevel ?? "").trim();
      return totalLevel || "--";
    };

    const isPassed = (record) => {
      const score = toNumber(mainScore(record));
      return /cet[ -]*[46]|英语.*[四六]级|四六级/i.test(String(record.examName || ""))
        && score !== null && score > 425;
    };

    const sortedRecords = () =>
      [...levelExams].sort((left, right) => {
        const leftTime = Date.parse(String(left.examDate || ""));
        const rightTime = Date.parse(String(right.examDate || ""));
        if (Number.isFinite(leftTime) && Number.isFinite(rightTime)) return rightTime - leftTime;
        return Number(right.index || 0) - Number(left.index || 0);
      });

    const renderLevelExams = () => {
      const count = document.getElementById("recordCount");
      const list = document.getElementById("levelExamList");
      if (count) count.textContent = String(levelExams.length);
      if (!(list instanceof HTMLElement)) return;

      if (!levelExams.length) {
        list.innerHTML = '<section class="empty">暂无等级考试记录</section>';
        return;
      }

      list.innerHTML = sortedRecords().map((record) => {
        const passed = isPassed(record);
        const score = mainScore(record);
        return (
          '<article class="record-card">' +
            '<div class="record-head">' +
              '<span class="record-icon" aria-hidden="true"><span></span></span>' +
              '<h2>' + escapeHtml(record.examName || "未命名考试") + '</h2>' +
              '<div class="total ' + (passed ? "passed" : "idle") + '">' +
                '<span>总分：</span><strong>' + escapeHtml(score) + '</strong>' +
              '</div>' +
            '</div>' +
            '<div class="detail-list">' +
              '<div class="detail-row"><span>笔试</span><strong>' + escapeHtml(displayPart(record.writtenScore || record.writtenLevel)) + '</strong></div>' +
              '<div class="detail-row"><span>机考</span><strong>' + escapeHtml(displayPart(record.computerScore || record.computerLevel)) + '</strong></div>' +
              '<div class="detail-row"><span>时间</span><strong>' + escapeHtml(record.examDate || "--") + '</strong></div>' +
            '</div>' +
          '</article>'
        );
      }).join("");
    };

    renderLevelExams();
  </script>
`;

export const renderLevelExamPage = (records: LevelExamRecord[]): string => `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <title>等级考试</title>
  <style>
    :root {
      --paper: #f3f3f4;
      --card: #ffffff;
      --line: #ececee;
      --ink: #62666d;
      --green: #67cfa7;
      --gold: #efc14f;
      --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
      --font-ui: "PingFang SC", "Microsoft YaHei", sans-serif;
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

    .app {
      max-width: 760px;
      min-height: 100vh;
      margin: 0 auto;
      background: var(--paper);
    }

    .summary-band {
      min-height: 38px;
      display: flex;
      align-items: center;
      padding: 0 22px;
      background: #f0f0f1;
      border-bottom: 1px solid #e7e7e8;
      color: #777a80;
      font-family: var(--font-cn);
      font-size: clamp(12px, calc(4.7vw - 8px), 24px);
      font-weight: 400;
      letter-spacing: 0;
    }

    .record-list {
      display: grid;
      gap: 7px;
    }

    .record-card {
      background: var(--card);
      border-top: 1px solid #eeeeef;
      border-bottom: 2px solid #eeeeef;
    }

    .record-head {
      min-height: 42px;
      display: grid;
      grid-template-columns: 46px minmax(0, 1fr) auto;
      gap: 10px;
      align-items: center;
      padding: 0 18px 0 22px;
      border-bottom: 1px solid var(--line);
    }

    .record-icon {
      width: 30px;
      height: 30px;
      border-radius: 999px;
      display: grid;
      place-items: center;
      background: var(--green);
      justify-self: start;
    }

    .record-icon span {
      width: 13px;
      height: 16px;
      border-radius: 2px;
      background: #fff;
      position: relative;
      box-shadow: inset 0 -1px 0 rgba(103, 207, 167, 0.14);
    }

    .record-icon span::before,
    .record-icon span::after {
      content: "";
      position: absolute;
      left: 3px;
      right: 3px;
      height: 1px;
      background: var(--green);
    }

    .record-icon span::before { top: 5px; }
    .record-icon span::after { top: 9px; }

    .record-head h2 {
      min-width: 0;
      margin: 0;
      color: #595d64;
      font-family: var(--font-cn);
      font-size: clamp(15px, calc(4.7vw - 3px), 28px);
      font-weight: 600;
      line-height: max(1.2em, calc(1.3em - 5px));
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      letter-spacing: 0;
    }

    .total {
      display: flex;
      align-items: baseline;
      justify-content: flex-end;
      gap: 8px;
      min-width: max-content;
      color: #777a80;
      font-family: var(--font-cn);
      font-size: clamp(15px, calc(5.2vw - 5px), 29px);
      line-height: calc(1em - 5px);
    }

    .total.passed {
      color: var(--gold);
    }

    .total strong {
      font-family: var(--font-ui);
      font-size: clamp(15px, calc(5.2vw - 5px), 29px);
      font-weight: 500;
      letter-spacing: 0;
    }

    .detail-list {
      padding: 12px 26px 14px;
      display: grid;
      gap: 18px;
    }

    .detail-row {
      display: grid;
      grid-template-columns: minmax(80px, 28%) minmax(0, 1fr);
      align-items: center;
      gap: 8px;
      min-height: 15px;
      font-family: var(--font-cn);
      font-size: clamp(13px, calc(4.6vw - 6px), 25px);
      font-weight: 400;
      line-height: max(1em, calc(1.2em - 3px));
    }

    .detail-row span {
      color: #60646b;
    }

    .detail-row strong {
      min-width: 0;
      justify-self: end;
      color: #64676d;
      font-family: var(--font-ui);
      font-size: clamp(13px, calc(4.5vw - 5px), 25px);
      font-weight: 400;
      text-align: right;
      overflow-wrap: anywhere;
    }

    .empty {
      min-height: 150px;
      display: grid;
      place-items: center;
      background: #fff;
      color: #9da1a8;
      font-family: var(--font-cn);
      font-size: 15px;
    }

    @media (max-width: 430px) {
      .record-head {
        grid-template-columns: 36px minmax(0, 1fr) auto;
        gap: 8px;
        padding-left: 16px;
        padding-right: 14px;
      }

      .record-icon {
        width: 26px;
        height: 26px;
      }

      .record-head h2 {
        font-size: 13px;
      }

      .total {
        gap: 5px;
        font-size: 13px;
      }

      .total strong {
        font-size: 13px;
      }

      .detail-list {
        padding-left: 20px;
        padding-right: 20px;
      }
    }
  </style>
</head>
<body>
  <div class="app">
    <section class="summary-band">考试记录（<span id="recordCount">${records.length}</span>条）</section>
    <main id="levelExamList" class="record-list" aria-label="等级考试记录">
      ${records.length === 0 ? '<section class="empty">暂无等级考试记录</section>' : ""}
    </main>
  </div>
  ${buildLevelExamPageScript(serializeForScript(records))}
</body>
</html>`;
