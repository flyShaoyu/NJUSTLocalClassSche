import { ScoreRecord } from "./types.js";

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");

const buildScorePageScript = (scoresJson: string): string => `
  <script>
    const rawScores = ${scoresJson};
    const scoreValueMap = {
      "优+": 98,
      "优": 95,
      "优-": 90,
      "良+": 88,
      "良": 85,
      "良-": 82,
      "中+": 78,
      "中": 75,
      "中-": 72,
      "及格": 65,
      "合格": 80,
      "通过": 80,
      "不及格": 50,
      "未通过": 50,
      "缓考": 0,
      "缺考": 0,
      "弃考": 0,
      "免修": 90
    };

    const escapeHtml = (value) => String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/\"/g, "&quot;");

    const normalize = (value) => String(value ?? "").replace(/\\s+/g, "").trim();

    const toNumber = (value) => {
      const parsed = Number(String(value ?? "").trim());
      return Number.isFinite(parsed) ? parsed : null;
    };

    const scoreToNumeric = (value) => {
      const text = String(value ?? "").trim();
      const numeric = toNumber(text);
      if (numeric !== null) return numeric;
      return scoreValueMap[text] ?? null;
    };

    const scoreToPoint = (value) => {
      const text = String(value ?? "").trim();
      if (text === "优+") return 4.1;
      if (text === "优") return 4.0;
      if (text === "优-") return 3.7;
      if (text === "良+") return 3.3;
      if (text === "良") return 3.0;
      if (text === "良-") return 2.7;
      if (text === "中+") return 2.3;
      if (text === "中") return 2.0;
      if (text === "中-") return 1.7;
      if (text === "及格" || text === "合格" || text === "通过") return 1.0;
      if (["不及格", "未通过", "缓考", "缺考", "弃考"].includes(text)) return 0;

      const numeric = scoreToNumeric(text);
      if (numeric === null) return null;
      if (numeric >= 90) return 4.0;
      if (numeric >= 85) return 3.7;
      if (numeric >= 82) return 3.3;
      if (numeric >= 78) return 3.0;
      if (numeric >= 75) return 2.7;
      if (numeric >= 72) return 2.3;
      if (numeric >= 68) return 2.0;
      if (numeric >= 64) return 1.5;
      if (numeric >= 60) return 1.0;
      return 0;
    };

    const isPassed = (value) => {
      const text = String(value ?? "").trim();
      if (!text) return false;
      if (["不及格", "未通过", "缓考", "缺考", "弃考"].includes(text)) return false;
      const numeric = scoreToNumeric(text);
      if (numeric !== null) return numeric >= 60;
      return true;
    };

    const isRequired = (record) => {
      const haystack = [record.courseAttribute, record.courseNature]
        .map((item) => String(item ?? ""))
        .join("|");
      return /必修/.test(haystack);
    };

    const semesterSortKey = (semester) => {
      const match = String(semester || "").match(/^(\\d{4})-(\\d{4})-(\\d)$/);
      if (!match) return 0;
      return Number(match[1]) * 10 + Number(match[3]);
    };

    const semesterLabelFromCode = (semester) => {
      const match = String(semester || "").match(/^(\\d{4})-(\\d{4})-(\\d)$/);
      if (!match) return semester || "";

      const academicYears = [...new Set(
        rawScores
          .map((record) => String(record.semester || "").match(/^(\\d{4})-(\\d{4})-(\\d)$/)?.[1])
          .filter(Boolean)
      )]
        .map((value) => Number(value))
        .sort((left, right) => left - right);
      const academicYearIndex = academicYears.indexOf(Number(match[1]));
      const yearLabelMap = ["", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十"];
      const yearNumber = academicYearIndex >= 0 ? academicYearIndex + 1 : Number(match[1]);
      const yearLabel = "大" + (yearLabelMap[yearNumber] || String(yearNumber));
      const termLabelMap = {
        "1": "上学期",
        "2": "下学期",
        "3": "暑期小学期"
      };
      return yearLabel + (termLabelMap[match[3]] || ("-" + match[3]));
    };

    const records = rawScores.map((record, index) => {
      const numericScore = scoreToNumeric(record.score);
      const gradePoint = scoreToPoint(record.score);
      const credits = toNumber(record.credits) ?? 0;
      const key = normalize(record.courseCode) || normalize(record.courseName);
      return {
        ...record,
        uiId: "score-" + index,
        key,
        numericScore,
        gradePoint,
        credits,
        passed: isPassed(record.score),
        required: isRequired(record),
        semesterLabel: semesterLabelFromCode(record.semester)
      };
    });

    const chooseDefaultSelectedIds = () => {
      const bestByCourse = new Map();

      records.forEach((record, index) => {
        if (!record.required) return;

        if (!record.key) {
          bestByCourse.set(record.uiId, {
            index,
            score: record.numericScore ?? -1,
            passed: record.passed
          });
          return;
        }

        const current = bestByCourse.get(record.key);
        const candidate = {
          index,
          score: record.numericScore ?? -1,
          passed: record.passed
        };

        if (!current) {
          bestByCourse.set(record.key, candidate);
          return;
        }

        if (candidate.passed && !current.passed) {
          bestByCourse.set(record.key, candidate);
          return;
        }

        if (candidate.passed === current.passed && candidate.score >= current.score) {
          bestByCourse.set(record.key, candidate);
        }
      });

      const selected = new Set();
      records.forEach((record) => {
        if (!record.required) return;
        const key = record.key || record.uiId;
        const best = bestByCourse.get(key);
        if (best && records[best.index]?.uiId === record.uiId) {
          selected.add(record.uiId);
        }
      });
      return selected;
    };

    const state = {
      selectedIds: chooseDefaultSelectedIds()
    };

    const groupedSemesters = () => {
      const groups = new Map();
      records.forEach((record) => {
        const list = groups.get(record.semester) || [];
        list.push(record);
        groups.set(record.semester, list);
      });

      return [...groups.entries()]
        .sort((left, right) => semesterSortKey(right[0]) - semesterSortKey(left[0]))
        .map(([semester, items]) => ({ semester, items }));
    };

    const selectedRecords = () => records.filter((record) => state.selectedIds.has(record.uiId));

    const computeMetrics = (items) => {
      const creditBearing = items.filter((item) => item.credits > 0);
      const totalCredits = creditBearing.reduce((sum, item) => sum + item.credits, 0);
      const weightedItems = creditBearing.filter((item) => item.numericScore !== null);
      const weightedAverage = totalCredits > 0 && weightedItems.length
        ? weightedItems.reduce((sum, item) => sum + item.numericScore * item.credits, 0) / totalCredits
        : null;
      const pointItems = creditBearing.filter((item) => item.gradePoint !== null);
      const gpa = totalCredits > 0 && pointItems.length
        ? pointItems.reduce((sum, item) => sum + item.gradePoint * item.credits, 0) / totalCredits
        : null;
      return { totalCredits, weightedAverage, gpa };
    };

    const formatCredits = (value) => {
      if (!value) return "--";
      const rounded = Math.round(value * 10) / 10;
      return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
    };

    const formatDecimal = (value) => {
      if (value === null || !Number.isFinite(value)) return "--";
      return value.toFixed(3).replace(/\\.000$/, "").replace(/(\\.\\d*[1-9])0+$/, "$1");
    };

    const formatPoint = (value) => {
      if (value === null || !Number.isFinite(value)) return "--";
      return value.toFixed(1).replace(/\\.0$/, "");
    };

    const summaryRows = () => {
      const selected = selectedRecords();
      return [
        { label: "全部课程", items: records },
        { label: "当前勾选", items: selected },
        { label: "必修课程", items: selected.filter((item) => item.required) }
      ];
    };

    const badgeText = (record) => {
      if (record.required) return "必修";
      return record.courseAttribute || "选修";
    };

    const gradePointLabel = (record) => formatPoint(record.gradePoint);

    const semesterSummary = (items) => {
      const selectedItems = items.filter((item) => state.selectedIds.has(item.uiId));
      const allMetrics = computeMetrics(items);
      const selectedMetrics = computeMetrics(selectedItems);
      return {
        selectedCount: selectedItems.length,
        rows: [
          {
            label: "全部",
            count: items.length,
            totalCredits: allMetrics.totalCredits,
            weightedAverage: allMetrics.weightedAverage,
            gpa: allMetrics.gpa
          },
          {
            label: "勾选",
            count: selectedItems.length,
            totalCredits: selectedMetrics.totalCredits,
            weightedAverage: selectedMetrics.weightedAverage,
            gpa: selectedMetrics.gpa
          }
        ]
      };
    };

    const renderSummary = () => {
      const table = document.getElementById("summaryTable");
      const hint = document.getElementById("selectionHint");
      if (!(table instanceof HTMLElement) || !(hint instanceof HTMLElement)) return;

      const selected = selectedRecords();
      hint.textContent =
        "默认勾选每门课当前计入的一次成绩，可点击课程卡片自行调整。当前共勾选 " +
        selected.length +
        " 门。";

      table.innerHTML = summaryRows().map((row) => {
        const metrics = computeMetrics(row.items);
        return (
          '<div class="summary-row">' +
            '<div class="summary-cell type">' + escapeHtml(row.label) + "</div>" +
            '<div class="summary-cell">' + escapeHtml(formatCredits(metrics.totalCredits)) + "</div>" +
            '<div class="summary-cell">' + escapeHtml(formatDecimal(metrics.weightedAverage)) + "</div>" +
            '<div class="summary-cell">' + escapeHtml(formatDecimal(metrics.gpa)) + "</div>" +
          "</div>"
        );
      }).join("");
    };

    const renderSemesters = () => {
      const list = document.getElementById("semesterList");
      if (!(list instanceof HTMLElement)) return;

      if (!records.length) {
        list.innerHTML = '<div class="empty">暂无成绩数据</div>';
        return;
      }

      const selectedKeys = new Set(selectedRecords().map((record) => record.key));
      list.innerHTML = groupedSemesters().map(({ semester, items }) => {
        const label = items[0]?.semesterLabel || semester;
        const summary = semesterSummary(items);
        const allSelected = items.every((record) => state.selectedIds.has(record.uiId));
        const rows = items.map((record) => {
          const selected = state.selectedIds.has(record.uiId);
          const superseded = !selected && record.key && selectedKeys.has(record.key);
          const classes = [
            "score-row",
            selected ? "selected" : "idle",
            record.isHighlighted ? "highlighted" : "",
            superseded ? "superseded" : ""
          ].filter(Boolean).join(" ");

          return (
            '<button type="button" class="' + classes + '" data-score-id="' + escapeHtml(record.uiId) + '">' +
              '<span class="check ' + (selected ? "on" : "off") + '">' + (selected ? "✓" : "") + "</span>" +
              '<span class="course">' +
                '<strong>' + escapeHtml(record.courseName) + "</strong>" +
              "</span>" +
              '<span class="value score">' + escapeHtml(record.score || "--") + "</span>" +
              '<span class="value credits">' + escapeHtml(formatCredits(record.credits)) + "</span>" +
              '<span class="value kind">' + escapeHtml(badgeText(record)) + "</span>" +
              '<span class="value point">' + escapeHtml(gradePointLabel(record)) + "</span>" +
            "</button>"
          );
        }).join("");

        return (
          '<section class="semester-card">' +
            '<div class="semester-head">' +
              '<div class="semester-icon" aria-hidden="true">' +
                '<span></span><span></span><span></span>' +
              "</div>" +
              '<div class="semester-meta">' +
                '<h2>' + escapeHtml(semester) + "</h2>" +
                '<p>' + escapeHtml(label) + "</p>" +
              "</div>" +
            "</div>" +
            '<div class="score-table">' +
              '<div class="table-head">' +
                '<button type="button" class="semester-check ' + (allSelected ? "on" : "off") + '" data-semester-code="' + escapeHtml(semester) + '" aria-label="全选或全不选当前学期">' +
                  '<span>' + (allSelected ? "√" : "") + '</span>' +
                '</button>' +
                '<span>课程</span><span>成绩</span><span>学分</span><span>类型</span><span>绩点</span>' +
              "</div>" +
              rows +
            "</div>" +
            '<div class="semester-summary">' +
              '<div class="semester-summary-head">' +
                '<span>范围</span><span>课程</span><span>学分</span><span>均分</span><span>GPA</span>' +
              "</div>" +
              summary.rows.map((row) => (
                '<div class="semester-summary-body">' +
                  '<span>' + escapeHtml(row.label) + "</span>" +
                  '<span>' + escapeHtml(String(row.count)) + "</span>" +
                  '<span>' + escapeHtml(formatCredits(row.totalCredits)) + "</span>" +
                  '<span>' + escapeHtml(formatDecimal(row.weightedAverage)) + "</span>" +
                  '<span>' + escapeHtml(formatDecimal(row.gpa)) + "</span>" +
                "</div>"
              )).join("") +
            "</div>" +
          "</section>"
        );
      }).join("");
    };

    const toggleSemester = (semester) => {
      const semesterRecords = records.filter((record) => record.semester === semester);
      if (!semesterRecords.length) return;

      const allSelected = semesterRecords.every((record) => state.selectedIds.has(record.uiId));
      semesterRecords.forEach((record) => {
        if (allSelected) {
          state.selectedIds.delete(record.uiId);
        } else {
          state.selectedIds.add(record.uiId);
        }
      });

      renderSummary();
      renderSemesters();
    };

    const toggleRecord = (id) => {
      if (state.selectedIds.has(id)) {
        state.selectedIds.delete(id);
      } else {
        state.selectedIds.add(id);
      }
      renderSummary();
      renderSemesters();
    };

    document.addEventListener("click", (event) => {
      const target = event.target;
      if (!(target instanceof HTMLElement)) return;

      const semesterButton = target.closest("[data-semester-code]");
      if (semesterButton instanceof HTMLElement) {
        toggleSemester(semesterButton.getAttribute("data-semester-code") || "");
        return;
      }

      const row = target.closest("[data-score-id]");
      if (row instanceof HTMLElement) {
        toggleRecord(row.getAttribute("data-score-id") || "");
      }
    });

    renderSummary();
    renderSemesters();
  </script>
`;

export const renderScorePage = (scores: ScoreRecord[]): string => `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <title>成绩查询</title>
  <style>
    :root {
      --sky: #74a7de;
      --sky-deep: #5f92cf;
      --paper: #f3f4f7;
      --card: #ffffff;
      --muted-card: #ededee;
      --line: rgba(120, 134, 156, 0.15);
      --ink: #5f6670;
      --muted: #a7aab0;
      --selected: #dcebff;
      --selected-strong: #69a8ea;
      --danger: #c66b63;
      --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
      --font-ui: "PingFang SC", "Microsoft YaHei", sans-serif;
    }

    * {
      box-sizing: border-box;
    }

    html,
    body {
      margin: 0;
      min-height: 100%;
      background: var(--paper);
      color: var(--ink);
      font-family: var(--font-ui);
    }

    button {
      font: inherit;
    }

    .app {
      min-height: 100vh;
      background: var(--paper);
    }

    .content {
      padding: 16px 12px 22px;
    }

    .panel,
    .semester-card {
      background: var(--card);
      box-shadow: 0 10px 28px rgba(111, 121, 138, 0.08);
      overflow: hidden;
    }

    .panel {
      padding: 16px 14px 14px;
      margin-bottom: 12px;
    }

    .semester-card {
      padding: 12px 10px 10px;
    }

    .panel h1 {
      margin: 0;
      text-align: center;
      font-family: var(--font-cn);
      font-size: clamp(15px, 3.4vw, 18px);
      font-weight: 600;
      color: #565a61;
      letter-spacing: 1px;
    }

    .panel-head {
      display: flex;
      align-items: center;
      justify-content: flex-start;
      gap: 8px;
      margin-top: 10px;
      color: #b4b5b9;
      font-family: var(--font-cn);
      font-size: 12px;
    }

    .selection-hint {
      margin: 10px 2px 0;
      color: #adb0b7;
      font-size: 12px;
      line-height: 1.6;
    }

    .summary-table {
      margin-top: 14px;
      overflow: hidden;
      background: #f5f5f6;
    }

    .summary-head,
    .summary-row {
      display: grid;
      grid-template-columns: 1.2fr 0.8fr 1fr 0.8fr;
      align-items: center;
      text-align: center;
    }

    .summary-head {
      min-height: 30px;
      background: #d7d7d9;
      color: #62656a;
      font-family: var(--font-cn);
      font-size: 15px;
    }

    .summary-row {
      min-height: 44px;
      background: #ececed;
      color: #62656a;
      font-size: 12px;
      border-top: 4px solid #fff;
    }

    .summary-row .type {
      font-family: var(--font-cn);
      font-weight: 600;
      font-size: 15px;
    }

    .semester-head {
      display: grid;
      grid-template-columns: 46px 1fr;
      gap: 8px;
      align-items: center;
      margin-bottom: 8px;
    }

    .semester-check {
      width: 20px;
      height: 20px;
      display: grid;
      place-items: center;
      padding: 0;
      border: 1px solid rgba(124, 132, 146, 0.22);
      background: #fff;
      color: transparent;
      cursor: pointer;
      justify-self: center;
      box-shadow: 0 2px 5px rgba(130, 136, 149, 0.08);
    }

    .semester-check span {
      font-family: sans-serif;
      font-size: 12px;
      font-weight: 400;
      line-height: 1;
      transform: translateY(-0.5px);
    }

    .semester-check.on {
      background: linear-gradient(180deg, #7eb9f2, #5e9de5);
      color: #fff;
      border-color: rgba(87, 145, 220, 0.2);
    }

    .semester-icon {
      height: 44px;
      display: flex;
      align-items: end;
      gap: 4px;
      padding: 5px 4px 6px;
      position: relative;
    }

    .semester-icon::after {
      content: "";
      position: absolute;
      left: 2px;
      right: 2px;
      bottom: 0;
      height: 4px;
      background: #b8bbc2;
    }

    .semester-icon span {
      width: 8px;
      border: 3px solid #aeb3bc;
      border-bottom-width: 4px;
      background: #fff;
    }

    .semester-icon span:nth-child(1) { height: 24px; }
    .semester-icon span:nth-child(2) { height: 36px; }
    .semester-icon span:nth-child(3) { height: 20px; }

    .semester-meta h2 {
      margin: 0;
      color: #5a5e66;
      font-size: clamp(11px, 3vw, 14px);
      font-weight: 500;
      letter-spacing: 1px;
    }

    .semester-meta p {
      margin: 3px 0 0;
      color: #9a9ea6;
      font-family: var(--font-cn);
      font-size: 12px;
    }

    .semester-meta small {
      display: block;
      margin-top: 4px;
      color: #afb3bb;
      font-size: 12px;
    }

    .semester-summary {
      margin-top: 6px;
      background: #eff1f5;
      overflow: hidden;
    }

    .semester-summary-head,
    .semester-summary-body {
      display: grid;
      grid-template-columns: 0.8fr 0.8fr 1fr 1fr 1fr;
      text-align: center;
      align-items: center;
      padding: 0 4px;
      min-height: 0;
    }

    .semester-summary-head {
      min-height: 20px;
    }

    .semester-summary-body {
      min-height: 26px;
    }

    .score-table {
      margin-top: 6px;
      overflow: hidden;
      background: #f7f8fb;
    }

    .table-head,
    .score-row {
      display: grid;
      grid-template-columns: 50px minmax(0, 1.7fr) 0.65fr 0.6fr 0.75fr 0.55fr;
      align-items: center;
      gap: 8px;
    }

    .table-head {
      min-height: 30px;
      padding: 0 2px;
      background: #d9d9db;
      color: #686b70;
      font-family: var(--font-cn);
      font-size: 13px;
      text-align: center;
      gap: 4px;
    }

    .table-head span:nth-child(2) {
      text-align: left;
      margin-left: -22px;
    }

    .table-head .semester-check {
      justify-self: center;
      margin-left: -22px;
    }

    .score-row {
      width: 100%;
      min-height: 46px;
      padding: 0 2px;
      border: 0;
      background: #ededee;
      color: #646971;
      text-align: left;
      border-top: 3px solid #fff;
      cursor: pointer;
    }

    .score-row.selected {
      background: var(--selected);
    }

    .score-row.superseded {
      opacity: 0.72;
    }

    .score-row.highlighted .score {
      color: var(--danger);
    }

    .check {
      width: 15px;
      height: 15px;
      display: grid;
      place-items: center;
      font-size: 12px;
      font-family: sans-serif;
      font-weight: 400;
      justify-self: start;
      margin-left: 6px;
      border: 1px solid rgba(124, 132, 146, 0.18);
      box-shadow: 0 2px 6px rgba(130, 136, 149, 0.1);
    }

    .check.off {
      background: #fff;
      color: transparent;
    }

    .check.on {
      background: linear-gradient(180deg, #7eb9f2, #5e9de5);
      color: #fff;
      border-color: rgba(87, 145, 220, 0.2);
    }

    .course {
      min-width: 0;
      display: grid;
      gap: 3px;
      padding-right: 0;
      margin-left: -25px;
    }

    .course strong {
      color: #565d67;
      font-family: var(--font-cn);
      font-size: 12px;
      font-weight: 600;
      line-height: 1.2;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .value {
      text-align: center;
      color: #60656d;
      font-size: 12px;
      white-space: nowrap;
    }

    .kind {
      font-family: var(--font-cn);
      font-size: 12px;
    }

    .point {
      font-size: 12px;
    }

    .score-row.highlighted .score {
      font-size: 13px;
    }

    .empty {
      min-height: 260px;
      display: grid;
      place-items: center;
      color: #aeb2b8;
      font-family: var(--font-cn);
      font-size: 12px;
      background: #fff;
      box-shadow: 0 10px 28px rgba(111, 121, 138, 0.08);
    }

    .tip {
      position: fixed;
      left: 50%;
      bottom: 28px;
      transform: translate(-50%, 20px);
      max-width: calc(100vw - 36px);
      padding: 12px 16px;
      background: rgba(74, 89, 112, 0.96);
      color: #fff;
      font-size: 12px;
      line-height: 1.5;
      opacity: 0;
      pointer-events: none;
      transition: opacity 180ms ease, transform 180ms ease;
      z-index: 10;
    }

    .tip.show {
      opacity: 1;
      transform: translate(-50%, 0);
    }

    @media (min-width: 760px) {
      .app {
        max-width: 760px;
        margin: 0 auto;
      }
    }

    .semester-summary-head {
      min-height: 22px;
      background: #dbdee4;
      color: #7b8089;
      font-family: var(--font-cn);
      font-size: 12px;
    }

    .semester-summary-body {
      min-height: 34px;
      color: #60656d;
      font-size: 12px;
      border-top: 2px solid #fff;
    }
  </style>
</head>
<body>
  <div class="app">
    <main class="content">
      <section class="panel">
        <h1>全部已修课程</h1>
        <div class="summary-table">
          <div class="summary-head">
            <div>课程类型</div>
            <div>总学分</div>
            <div>加权平均分</div>
            <div>GPA</div>
          </div>
          <div id="summaryTable"></div>
        </div>
        <p id="selectionHint" class="selection-hint"></p>
      </section>

      <section id="semesterList" class="semester-list">
        ${scores.length === 0 ? '<div class="empty">暂无成绩数据</div>' : ""}
      </section>
    </main>
  </div>

  <div id="tip" class="tip"></div>
  ${buildScorePageScript(serializeForScript(scores))}
</body>
</html>`;

