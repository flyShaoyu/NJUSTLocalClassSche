import { TimetableCourse } from "./types.js";

const WEEKDAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"];
const ANCHOR_WEEK = 6;
const ANCHOR_MONDAY = "2026-04-06";

const PERIOD_SLOTS: Record<number, { start: string; end: string }> = {
  1: { start: "08:00", end: "08:45" },
  2: { start: "08:50", end: "09:35" },
  3: { start: "09:40", end: "10:25" },
  4: { start: "10:40", end: "11:25" },
  5: { start: "11:30", end: "12:15" },
  6: { start: "14:00", end: "14:45" },
  7: { start: "14:50", end: "15:35" },
  8: { start: "15:50", end: "16:35" },
  9: { start: "16:40", end: "17:25" },
  10: { start: "17:30", end: "18:15" },
  11: { start: "19:00", end: "19:45" },
  12: { start: "19:50", end: "20:35" },
  13: { start: "20:40", end: "21:25" },
  14: { start: "12:15", end: "14:00" }
};

const COLOR_PALETTE = [
  ["#f39ac3", "#d97daa"],
  ["#ffd36f", "#f0ba4d"],
  ["#7ed2aa", "#66bf94"],
  ["#78b7ee", "#5f9de0"],
  ["#628fe2", "#4a7cd1"],
  ["#96cf6e", "#80ba5a"],
  ["#e25555", "#cf4444"],
  ["#6e7fd8", "#5a69c2"]
];

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");

const buildInitialWeek = (courses: TimetableCourse[]): number => {
  const weeks = new Set<number>();

  for (const course of courses) {
    const matches = course.weeks.match(/\d+(?:-\d+)?/g) ?? [];
    for (const match of matches) {
      if (match.includes("-")) {
        const [startText, endText] = match.split("-");
        const start = Number(startText);
        const end = Number(endText);

        if (Number.isFinite(start) && Number.isFinite(end)) {
          for (let week = start; week <= end; week += 1) {
            weeks.add(week);
          }
        }
      } else {
        const week = Number(match);
        if (Number.isFinite(week)) {
          weeks.add(week);
        }
      }
    }
  }

  const sortedWeeks = [...weeks].sort((left, right) => left - right);
  return sortedWeeks.includes(ANCHOR_WEEK) ? ANCHOR_WEEK : (sortedWeeks[0] ?? ANCHOR_WEEK);
};

export const renderTimetablePage = (courses: TimetableCourse[]): string => {
  const initialWeek = buildInitialWeek(courses);

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>课表查询</title>
  <style>
    :root {
      --sky-top: #6ca7e0;
      --sky-bottom: #79afe6;
      --paper: #f7f8fc;
      --ink: #3f5675;
      --muted: #95a5ba;
      --line: rgba(111, 138, 168, 0.16);
      --axis-width: clamp(36px, 8vw, 54px);
      --row-min-height: 84px;
      --row-height: clamp(var(--row-min-height), 8.5vw, 108px);
      --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
      --font-ui: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    }

    * { box-sizing: border-box; }

    body {
      margin: 0;
      min-height: 100vh;
      background:
        radial-gradient(circle at top left, rgba(255,255,255,0.48), transparent 16%),
        linear-gradient(180deg, #8ebcf0 0, #7fb2ea 228px, var(--paper) 228px, var(--paper) 100%);
      color: var(--ink);
      font-family: var(--font-ui);
      overflow-x: hidden;
    }

    body.sheet-open {
      overflow: hidden;
    }

    .app {
      max-width: 1180px;
      margin: 0 auto;
      min-height: 100vh;
      padding-bottom: 92px;
    }

    .hero {
      padding: 24px 18px 18px;
      color: white;
    }

    .hero-main,
    .sheet-topbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
    }

    .footer-side {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .hero-main {
      gap: 14px;
      justify-content: center;
    }

    .back-button,
    .icon-button {
      border: 0;
      background: transparent;
      color: white;
      cursor: pointer;
    }

    .hero-title {
      text-align: center;
      font-family: var(--font-cn);
      font-size: clamp(22px, 4.6vw, 30px);
      letter-spacing: clamp(2px, 0.9vw, 6px);
      text-shadow: 0 3px 10px rgba(55, 82, 118, 0.18);
    }

    .board {
      position: relative;
      background: rgba(255,255,255,0.88);
      border-radius: 22px 22px 0 0;
      box-shadow: 0 30px 65px rgba(66, 88, 128, 0.14);
      overflow: hidden;
      transition: filter 180ms ease, transform 180ms ease;
    }

    body.sheet-open .board {
      filter: blur(10px) saturate(0.85);
      transform: scale(0.995);
    }

    .board-highlight-layer {
      position: absolute;
      inset: 0;
      pointer-events: none;
      z-index: 0;
    }

    .board-column-highlight {
      position: absolute;
      top: 0;
      bottom: 0;
      border-radius: 14px;
      background: linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(234, 248, 237, 0.24) 22%, rgba(212, 241, 221, 0.48) 58%, rgba(182, 232, 196, 0.72) 100%);
      box-shadow: inset 0 0 0 1px rgba(155, 212, 172, 0.14);
    }

    .header-row {
      position: relative;
      z-index: 1;
      display: grid;
      grid-template-columns: var(--axis-width) repeat(7, minmax(0, 1fr));
      background: rgba(255,255,255,0.76);
      border-bottom: 1px solid rgba(111, 138, 168, 0.13);
    }

    .header-cell {
      min-height: 62px;
      padding: 8px 6px 10px;
      text-align: center;
    }

    .header-date {
      display: block;
      font-size: clamp(11px, 2.5vw, 13px);
      color: var(--muted);
      margin-bottom: 4px;
    }

    .header-weekday {
      display: block;
      font-family: var(--font-cn);
      font-size: clamp(15px, 3.4vw, 18px);
      color: #566c87;
    }

    .header-cell.today .header-date,
    .header-cell.today .header-weekday {
      color: #4d78b2;
      font-weight: 700;
    }

    .header-cell.today {
      background: linear-gradient(180deg, rgba(226, 244, 232, 0.9), rgba(255,255,255,0.82));
      box-shadow: inset 0 -1px 0 rgba(114, 174, 132, 0.14);
    }

    .grid-scroll {
      position: relative;
      z-index: 1;
      overflow: hidden;
      background:
        linear-gradient(90deg, rgba(188, 219, 255, 0.2), rgba(255,255,255,0) 24%),
        linear-gradient(180deg, rgba(255,255,255,0.9), rgba(246,248,252,0.98));
    }

    .schedule {
      position: relative;
      width: 100%;
      display: grid;
      grid-template-columns: var(--axis-width) repeat(7, minmax(0, 1fr));
      grid-template-rows: repeat(14, var(--row-height));
    }

    .axis-cell,
    .slot-cell,
    .course-card {
      min-height: var(--row-height);
    }

    .axis-cell {
      display: flex;
      align-items: flex-start;
      justify-content: center;
      padding-top: 10px;
      color: #7f8da0;
      font-size: clamp(13px, 3vw, 15px);
      border-right: 1px solid var(--line);
      border-bottom: 1px solid var(--line);
      background: rgba(255,255,255,0.68);
      z-index: 1;
    }

    .slot-cell {
      border-right: 1px solid var(--line);
      border-bottom: 1px solid var(--line);
      background:
        linear-gradient(180deg, rgba(255,255,255,0.56), rgba(248,250,255,0.9)),
        linear-gradient(90deg, rgba(180, 214, 255, 0.18), transparent 16%);
    }

    .schedule-column-highlight {
      position: absolute;
      z-index: 2;
      top: 0;
      bottom: 0;
      border-radius: 12px;
      background: linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(228, 246, 233, 0.24) 24%, rgba(202, 238, 213, 0.46) 62%, rgba(174, 229, 191, 0.68) 100%);
      box-shadow: inset 0 0 0 1px rgba(155, 212, 172, 0.12);
      pointer-events: none;
    }

    .course-card {
      position: relative;
      z-index: 3;
      margin: 4px;
      padding: 10px 10px 12px;
      border-radius: 12px;
      color: white;
      display: flex;
      flex-direction: column;
      justify-content: space-between;
      align-self: stretch;
      box-shadow: 0 10px 22px rgba(77, 102, 144, 0.2);
      border: 1px solid rgba(255,255,255,0.28);
      overflow: hidden;
      cursor: pointer;
      transition: transform 120ms ease, box-shadow 120ms ease;
    }

    .course-card:active {
      transform: scale(0.985);
      box-shadow: 0 6px 12px rgba(77, 102, 144, 0.18);
    }

    .course-card.active {
      transform: translateY(-2px) scale(1.01);
      box-shadow: 0 16px 28px rgba(64, 92, 138, 0.28);
    }

    .course-card::after {
      content: "";
      position: absolute;
      inset: 0;
      background: linear-gradient(180deg, rgba(255,255,255,0.18), transparent 38%, rgba(0,0,0,0.08));
      pointer-events: none;
    }

    .course-top,
    .course-title,
    .course-bottom {
      position: relative;
      z-index: 1;
    }

    .course-top {
      font-size: clamp(11px, 2.8vw, 15px);
      line-height: 1.15;
      text-shadow: 0 1px 2px rgba(0,0,0,0.12);
    }

    .course-room {
      margin-top: 2px;
      opacity: 0.96;
    }

    .course-title {
      margin: 12px 0 10px;
      text-align: center;
      font-family: var(--font-cn);
      font-size: clamp(14px, 3.3vw, 17px);
      line-height: 1.34;
      letter-spacing: clamp(0px, 0.2vw, 1px);
      word-break: break-word;
    }

    .course-bottom {
      display: flex;
      justify-content: flex-start;
      gap: 8px;
      font-size: clamp(10px, 2.3vw, 12px);
      opacity: 0.94;
    }

    .course-tap {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 3px 8px;
      border-radius: 999px;
      background: rgba(255,255,255,0.18);
      box-shadow: inset 0 1px 0 rgba(255,255,255,0.16);
    }

    .now-line {
      position: absolute;
      z-index: 6;
      height: 0;
      border-top: 3px solid #9fd3ff;
      box-shadow: 0 0 0 1px rgba(255,255,255,0.75), 0 0 16px rgba(122, 186, 255, 0.42);
      border-radius: 999px;
      pointer-events: none;
    }

    .now-dot {
      position: absolute;
      top: -5px;
      left: -5px;
      width: 10px;
      height: 10px;
      border-radius: 999px;
      background: #87c6ff;
      box-shadow: 0 0 0 2px rgba(255,255,255,0.9);
    }

    .legend {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      padding: 16px 16px 18px;
      border-top: 1px solid rgba(111, 138, 168, 0.12);
      background: rgba(255,255,255,0.72);
    }

    .legend-item {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 8px 12px;
      border-radius: 999px;
      background: rgba(248, 250, 255, 0.96);
      color: #617691;
      font-size: 13px;
    }

    .legend-dot {
      width: 12px;
      height: 12px;
      border-radius: 999px;
    }

    .week-empty {
      padding: 72px 20px 84px;
      text-align: center;
      color: #73859c;
      background: rgba(255,255,255,0.72);
    }

    .week-empty h2 {
      margin: 0 0 10px;
      font-family: var(--font-cn);
      font-size: 28px;
      color: #4d627c;
    }

    .footer-bar {
      position: fixed;
      left: max(0px, calc((100vw - 1180px) / 2));
      right: max(0px, calc((100vw - 1180px) / 2));
      bottom: 0;
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
      align-items: center;
      padding: 14px 18px calc(14px + env(safe-area-inset-bottom));
      border-radius: 20px 20px 0 0;
      color: white;
      background: linear-gradient(180deg, rgba(111,165,226,0.98), rgba(104,156,217,0.98));
      box-shadow: 0 -14px 30px rgba(55, 89, 136, 0.16);
      z-index: 1000;
    }

    .footer-side,
    .footer-center {
      font-family: var(--font-cn);
      font-size: clamp(16px, 3.8vw, 18px);
      letter-spacing: 1px;
    }

    .footer-side:first-child {
      justify-self: start;
    }

    .footer-center {
      display: flex;
      align-items: center;
      gap: 12px;
      justify-self: center;
    }

    .sheet-overlay {
      position: fixed;
      inset: 0;
      background: rgba(36, 50, 70, 0.22);
      opacity: 0;
      pointer-events: none;
      transition: opacity 180ms ease;
      z-index: 1080;
      backdrop-filter: blur(2px);
    }

    .sheet-overlay.show {
      opacity: 1;
      pointer-events: auto;
    }

    .detail-sheet {
      position: fixed;
      left: 0;
      right: 0;
      bottom: 0;
      min-height: 26vh;
      max-height: 42vh;
      padding: 10px 0 calc(16px + env(safe-area-inset-bottom));
      color: white;
      background: linear-gradient(180deg, rgba(112, 167, 228, 0.99), rgba(103, 156, 217, 0.99));
      box-shadow: 0 -18px 36px rgba(51, 83, 126, 0.2);
      border-radius: 22px 22px 0 0;
      transform: translateY(calc(100% + 12px));
      transition: transform 220ms ease;
      z-index: 1100;
      overflow: hidden;
    }

    .detail-sheet.show {
      transform: translateY(0);
    }

    .sheet-grabber {
      width: 60px;
      height: 5px;
      border-radius: 999px;
      margin: 0 auto 12px;
      background: rgba(255,255,255,0.55);
    }

    .sheet-topbar {
      padding: 0 22px 14px;
      font-family: var(--font-cn);
      font-size: clamp(16px, 4vw, 18px);
      border-bottom: 1px solid rgba(255,255,255,0.18);
    }

    .sheet-topbar-left {
      display: flex;
      align-items: center;
      gap: 18px;
      min-width: 0;
    }

    .sheet-title {
      margin: 0;
      padding: 18px 22px 12px;
      font-family: var(--font-cn);
      font-size: clamp(28px, 7.4vw, 42px);
      letter-spacing: 1px;
      line-height: 1.15;
    }

    .sheet-body {
      position: relative;
      padding: 8px 22px 0;
      min-height: 150px;
    }

    .sheet-watermark {
      position: absolute;
      right: 12px;
      bottom: -6px;
      font-size: clamp(96px, 22vw, 170px);
      line-height: 1;
      color: rgba(255,255,255,0.1);
      font-family: var(--font-cn);
      pointer-events: none;
      user-select: none;
    }

    .sheet-tag {
      position: absolute;
      left: 22px;
      bottom: 16px;
      padding: 8px 10px;
      border-radius: 12px;
      background: rgba(246, 116, 154, 0.92);
      font-family: var(--font-cn);
      font-size: clamp(14px, 3.8vw, 20px);
      line-height: 1.15;
      box-shadow: 0 8px 16px rgba(79, 88, 124, 0.16);
    }

    .sheet-meta {
      display: grid;
      gap: 10px;
      padding-left: 82px;
      padding-right: 24px;
      position: relative;
      z-index: 1;
    }

    .sheet-row {
      display: flex;
      align-items: baseline;
      gap: 10px;
      flex-wrap: wrap;
      font-family: var(--font-cn);
    }

    .sheet-main {
      font-size: clamp(24px, 6.8vw, 34px);
      line-height: 1.2;
    }

    .sheet-note {
      font-size: clamp(15px, 3.6vw, 20px);
      opacity: 0.96;
    }

    .sheet-close {
      border: 0;
      width: 32px;
      height: 32px;
      border-radius: 999px;
      background: rgba(255,255,255,0.14);
      color: white;
      font-size: 22px;
      cursor: pointer;
      line-height: 1;
    }

    @media (max-width: 720px) {
      .hero { padding-inline: 14px; }
      .footer-bar { left: 0; right: 0; }
      .board { border-radius: 18px 18px 0 0; }
      :root {
        --axis-width: 42px;
        --row-min-height: 76px;
      }
      .sheet-topbar { padding-inline: 16px; }
      .sheet-title { padding-inline: 16px; }
      .sheet-body { padding-inline: 16px; }
      .sheet-tag { left: 16px; }
      .sheet-meta { padding-left: 70px; padding-right: 10px; }
    }
  </style>
</head>
<body>
  <div class="app">
    <header class="hero">
      <div class="hero-main">
        <div class="hero-title">课表查询</div>
      </div>
    </header>

    <main class="board">
      <div id="boardHighlightLayer" class="board-highlight-layer"></div>
      <div id="headerRow" class="header-row"></div>
      <div class="grid-scroll">
        <div id="schedule" class="schedule"></div>
      </div>
      <div id="legend" class="legend"></div>
      <div id="emptyState" class="week-empty" hidden>
        <h2>这一周没有课</h2>
        <p>换一周看看，或者重新抓取一下最新课表。</p>
      </div>
    </main>
  </div>

  <footer class="footer-bar">
    <div id="footerDay" class="footer-side">星期一</div>
    <div class="footer-center">
      <button id="prevWeek" class="icon-button toolbar-button" type="button" aria-label="上一周">‹</button>
      <span id="weekLabel">第${initialWeek}周</span>
      <button id="nextWeek" class="icon-button toolbar-button" type="button" aria-label="下一周">›</button>
    </div>
    <div class="footer-side"></div>
  </footer>

  <div id="sheetOverlay" class="sheet-overlay"></div>
  <section id="detailSheet" class="detail-sheet" aria-hidden="true">
    <div class="sheet-grabber"></div>
    <div class="sheet-topbar">
      <div id="sheetTopbarLeft" class="sheet-topbar-left">
        <span id="sheetWeekDay">6周周五</span>
        <span id="sheetPeriodLine">4-5节 10:40-12:15</span>
      </div>
      <button id="sheetClose" class="sheet-close" type="button" aria-label="关闭">⌄</button>
    </div>
    <h2 id="sheetTitle" class="sheet-title">课程详情</h2>
    <div class="sheet-body">
      <div id="sheetWatermark" class="sheet-watermark">35</div>
      <div id="sheetTag" class="sheet-tag">必修</div>
      <div class="sheet-meta">
        <div class="sheet-row"><span id="sheetTeacher" class="sheet-main">-</span><span class="sheet-note">（任课教师）</span></div>
        <div class="sheet-row"><span id="sheetRoom" class="sheet-main">-</span><span class="sheet-note">（上课地点）</span></div>
        <div class="sheet-row"><span id="sheetWeeks" class="sheet-main">-</span><span class="sheet-note">（课程周数）</span></div>
        <div class="sheet-row"><span id="sheetCode" class="sheet-main">-</span><span class="sheet-note">（课程序号）</span></div>
      </div>
    </div>
  </section>

  <script>
    const courses = ${serializeForScript(courses)};
    const weekdays = ${serializeForScript(WEEKDAYS)};
    const periodSlots = ${serializeForScript(PERIOD_SLOTS)};
    const palette = ${serializeForScript(COLOR_PALETTE)};
    const anchorWeek = ${ANCHOR_WEEK};
    const anchorMonday = new Date(${serializeForScript(ANCHOR_MONDAY)} + 'T00:00:00');

    const normalizeCourses = courses.map((course) => {
      const periodMatch = course.periods.match(/(\\d+)-(\\d+)/);
      const startPeriod = periodMatch ? Number(periodMatch[1]) : 1;
      const endPeriod = periodMatch ? Number(periodMatch[2]) : startPeriod;
      const weeks = [];

      for (const token of course.weeks.match(/\\d+(?:-\\d+)?/g) || []) {
        if (token.includes('-')) {
          const [startText, endText] = token.split('-');
          const start = Number(startText);
          const end = Number(endText);
          for (let week = start; week <= end; week += 1) weeks.push(week);
        } else {
          weeks.push(Number(token));
        }
      }

      return {
        ...course,
        startPeriod,
        endPeriod,
        weeks,
        weeksText: course.weeks,
        paletteKey: course.courseName,
        courseKind: course.courseSequence === '0' ? '课程' : '必修'
      };
    });

    const allWeeks = [...new Set(normalizeCourses.flatMap((course) => course.weeks))].sort((a, b) => a - b);
    const colorMap = new Map();
    let paletteIndex = 0;

    for (const course of normalizeCourses) {
      if (!colorMap.has(course.paletteKey)) {
        colorMap.set(course.paletteKey, palette[paletteIndex % palette.length]);
        paletteIndex += 1;
      }
    }

    const headerRow = document.getElementById('headerRow');
    const board = document.querySelector('.board');
    const boardHighlightLayer = document.getElementById('boardHighlightLayer');
    const gridScroll = document.querySelector('.grid-scroll');
    const schedule = document.getElementById('schedule');
    const legend = document.getElementById('legend');
    const emptyState = document.getElementById('emptyState');
    const footerDay = document.getElementById('footerDay');
    const weekLabel = document.getElementById('weekLabel');
    const sheetOverlay = document.getElementById('sheetOverlay');
    const detailSheet = document.getElementById('detailSheet');
    const sheetTopbarLeft = document.getElementById('sheetTopbarLeft');
    const sheetWeekDay = document.getElementById('sheetWeekDay');
    const sheetPeriodLine = document.getElementById('sheetPeriodLine');
    const sheetTitle = document.getElementById('sheetTitle');
    const sheetTeacher = document.getElementById('sheetTeacher');
    const sheetRoom = document.getElementById('sheetRoom');
    const sheetWeeks = document.getElementById('sheetWeeks');
    const sheetCode = document.getElementById('sheetCode');
    const sheetWatermark = document.getElementById('sheetWatermark');
    const sheetTag = document.getElementById('sheetTag');
    const sheetClose = document.getElementById('sheetClose');

    const formatDateLabel = (date) => (date.getMonth() + 1) + '月' + date.getDate();

    const getWeekDates = (week) => {
      const weekOffset = (week - anchorWeek) * 7;
      const monday = new Date(anchorMonday);
      monday.setDate(anchorMonday.getDate() + weekOffset);
      return weekdays.map((_, index) => {
        const date = new Date(monday);
        date.setDate(monday.getDate() + index);
        return date;
      });
    };

    const getCurrentWeekByAnchor = () => {
      const now = new Date();
      const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      const diffDays = Math.floor((dayStart - anchorMonday) / (1000 * 60 * 60 * 24));
      return anchorWeek + Math.floor(diffDays / 7);
    };

    const getCurrentWeekdayLabel = () => {
      const now = new Date();
      const weekdayIndex = (now.getDay() + 6) % 7;
      return weekdays[weekdayIndex] || '星期一';
    };

    const getInitialStateWeek = () => {
      const currentWeek = getCurrentWeekByAnchor();
      if (allWeeks.includes(currentWeek)) {
        return currentWeek;
      }

      const futureWeek = allWeeks.find((week) => week >= currentWeek);
      if (futureWeek !== undefined) {
        return futureWeek;
      }

      return allWeeks[allWeeks.length - 1] || ${initialWeek};
    };

    const state = {
      week: getInitialStateWeek(),
      activeCourse: null
    };

    const closeSheet = () => {
      detailSheet.classList.remove('show');
      sheetOverlay.classList.remove('show');
      detailSheet.setAttribute('aria-hidden', 'true');
      document.body.classList.remove('sheet-open');
      schedule.querySelectorAll('.course-card.active').forEach((card) => card.classList.remove('active'));
    };

    const toMinutes = (time) => {
      const [hourText, minuteText] = time.split(':');
      return Number(hourText) * 60 + Number(minuteText);
    };

    const getCourseTimeRange = (course) => {
      const start = periodSlots[course.startPeriod]?.start || '';
      const end = periodSlots[course.endPeriod]?.end || '';
      return start + '-' + end;
    };

    const openSheet = (course, card) => {
      state.activeCourse = course;
      schedule.querySelectorAll('.course-card.active').forEach((node) => node.classList.remove('active'));
      card.classList.add('active');
      sheetWeekDay.textContent = state.week + '周' + course.weekday.replace('星期', '周');
      sheetPeriodLine.textContent = course.periods.replace('小节', '节 ') + getCourseTimeRange(course);
      sheetTitle.textContent = course.courseName;
      sheetTeacher.textContent = course.teacher || '未标注教师';
      sheetRoom.textContent = course.classroom || '待定地点';
      sheetWeeks.textContent = course.weeksText || '全学期';
      sheetCode.textContent = course.courseCode || '-';
      sheetWatermark.textContent = String(course.startPeriod) + String(course.endPeriod);
      sheetTag.textContent = course.courseKind;
      detailSheet.classList.add('show');
      sheetOverlay.classList.add('show');
      detailSheet.setAttribute('aria-hidden', 'false');
      document.body.classList.add('sheet-open');
    };

    const getCurrentDayIndex = () => {
      if (state.week !== getCurrentWeekByAnchor()) return -1;
      const now = new Date();
      const labels = getWeekDates(state.week).map(formatDateLabel);
      return labels.indexOf(formatDateLabel(now));
    };

    const renderNowLine = () => {
      const dayIndex = getCurrentDayIndex();
      if (dayIndex === -1) return;

      const now = new Date();
      const minutesNow = now.getHours() * 60 + now.getMinutes();
      const timelineEntries = Object.entries(periodSlots)
        .map(([period, slot]) => {
          return {
            period: Number(period),
            startMinutes: toMinutes(slot.start),
            endMinutes: toMinutes(slot.end)
          };
        })
        .sort((left, right) => left.startMinutes - right.startMinutes);

      const firstMinutes = timelineEntries[0]?.startMinutes;
      const lastMinutes = timelineEntries[timelineEntries.length - 1]?.endMinutes;
      if (firstMinutes === undefined || lastMinutes === undefined) return;

      const rowHeight = schedule.clientHeight / 14;
      let top = 0;

      if (minutesNow <= firstMinutes) {
        top = 0;
      } else if (minutesNow >= lastMinutes) {
        const lastEntry = timelineEntries[timelineEntries.length - 1];
        top = lastEntry.period * rowHeight;
      } else {
        for (let index = 0; index < timelineEntries.length; index += 1) {
          const current = timelineEntries[index];

          if (minutesNow >= current.startMinutes && minutesNow <= current.endMinutes) {
            const slotSpan = Math.max(1, current.endMinutes - current.startMinutes);
            const slotOffset = (minutesNow - current.startMinutes) / slotSpan;
            top = ((current.period - 1) + slotOffset) * rowHeight;
            break;
          }

          const next = timelineEntries[index + 1];
          if (next && minutesNow > current.endMinutes && minutesNow < next.startMinutes) {
            const gapSpan = Math.max(1, next.startMinutes - current.endMinutes);
            const gapOffset = (minutesNow - current.endMinutes) / gapSpan;
            const gapStartTop = current.period * rowHeight;
            const gapEndTop = (next.period - 1) * rowHeight;
            top = gapStartTop + (gapEndTop - gapStartTop) * gapOffset;
            break;
          }
        }
      }

      const line = document.createElement('div');
      line.className = 'now-line';
      const axisWidth = schedule.querySelector('.axis-cell')?.getBoundingClientRect().width || 0;
      const dayWidth = (schedule.clientWidth - axisWidth) / 7;
      line.style.left = axisWidth + dayIndex * dayWidth + 'px';
      line.style.width = Math.max(0, dayWidth) + 'px';
      line.style.top = top + 'px';
      line.innerHTML = '<span class="now-dot"></span>';
      schedule.appendChild(line);
    };

    const renderBoardHighlight = () => {
      boardHighlightLayer.innerHTML = '';
      const currentDayIndex = getCurrentDayIndex();
      if (currentDayIndex === -1 || !board || !gridScroll) return;

      const boardRect = board.getBoundingClientRect();
      const gridScrollRect = gridScroll.getBoundingClientRect();
      const headerCells = headerRow.querySelectorAll('.header-cell');
      const targetCell = headerCells[currentDayIndex + 1];
      if (!targetCell) return;

      const targetRect = targetCell.getBoundingClientRect();
      const highlight = document.createElement('div');
      highlight.className = 'board-column-highlight';
      highlight.style.top = targetRect.top - boardRect.top + 'px';
      highlight.style.height = gridScrollRect.bottom - targetRect.top + 'px';
      highlight.style.left = targetRect.left - boardRect.left + 4 + 'px';
      highlight.style.width = Math.max(0, targetRect.width - 8) + 'px';
      boardHighlightLayer.appendChild(highlight);
    };

    const renderHeader = () => {
      const weekDates = getWeekDates(state.week);
      const currentDayIndex = getCurrentDayIndex();
      headerRow.innerHTML = '<div class="header-cell"></div>' + weekdays.map((weekday, index) => {
        const isToday = index === currentDayIndex;
        return '<div class="header-cell' + (isToday ? ' today' : '') + '"><span class="header-date">' + formatDateLabel(weekDates[index]) + '</span><span class="header-weekday">' + weekday.replace('星期', '周') + '</span></div>';
      }).join('');
    };

    const renderBackgroundGrid = () => {
      schedule.innerHTML = '';
      const currentDayIndex = getCurrentDayIndex();

      if (currentDayIndex !== -1) {
        const axisWidth = headerRow.querySelector('.header-cell')?.getBoundingClientRect().width || 0;
        const dayWidth = (schedule.clientWidth - axisWidth) / 7;
        const columnHighlight = document.createElement('div');
        columnHighlight.className = 'schedule-column-highlight';
        columnHighlight.style.left = axisWidth + currentDayIndex * dayWidth + 4 + 'px';
        columnHighlight.style.width = Math.max(0, dayWidth - 8) + 'px';
        schedule.appendChild(columnHighlight);
      }

      for (let period = 1; period <= 14; period += 1) {
        const axis = document.createElement('div');
        axis.className = 'axis-cell';
        axis.style.gridColumn = '1';
        axis.style.gridRow = String(period);
        axis.textContent = String(period);
        schedule.appendChild(axis);

        for (let dayIndex = 0; dayIndex < weekdays.length; dayIndex += 1) {
          const slot = document.createElement('div');
          slot.className = 'slot-cell';
          slot.style.gridColumn = String(dayIndex + 2);
          slot.style.gridRow = String(period);
          schedule.appendChild(slot);
        }
      }
    };

    const renderLegend = (visibleCourses) => {
      const names = [...new Set(visibleCourses.map((course) => course.courseName))];
      legend.innerHTML = names.map((name) => {
        const [from, to] = colorMap.get(name);
        return '<div class="legend-item"><span class="legend-dot" style="background:linear-gradient(180deg,' + from + ',' + to + ')"></span>' + name + '</div>';
      }).join('');
    };

    const renderCards = (visibleCourses) => {
      for (const course of visibleCourses) {
        const dayIndex = weekdays.indexOf(course.weekday);
        if (dayIndex === -1) continue;

        const [from, to] = colorMap.get(course.paletteKey);
        const card = document.createElement('article');
        card.className = 'course-card';
        card.style.gridColumn = String(dayIndex + 2);
        card.style.gridRow = course.startPeriod + ' / ' + (course.endPeriod + 1);
        card.style.background = 'linear-gradient(180deg,' + from + ',' + to + ')';

        const time = periodSlots[course.startPeriod]?.start || '';
        const room = course.classroom || '待定地点';
        const shortLabel = course.courseCode || '详情';

        card.innerHTML = [
          '<div class="course-top"><div>' + time + '</div><div class="course-room">' + room + '</div></div>',
          '<div class="course-title">' + course.courseName + '</div>',
          '<div class="course-bottom"><span class="course-tap">' + shortLabel + '</span></div>'
        ].join('');

        card.title = course.courseName + ' | ' + course.weekday + ' ' + course.periods;
        card.addEventListener('click', () => openSheet(course, card));
        schedule.appendChild(card);
      }
    };

    const render = () => {
      const visibleCourses = normalizeCourses
        .filter((course) => course.weeks.length === 0 || course.weeks.includes(state.week))
        .sort((left, right) => {
          const dayDiff = weekdays.indexOf(left.weekday) - weekdays.indexOf(right.weekday);
          if (dayDiff !== 0) return dayDiff;
          return left.startPeriod - right.startPeriod;
        });

      closeSheet();
      renderHeader();
      weekLabel.textContent = '第' + state.week + '周';
      footerDay.textContent = getCurrentWeekdayLabel();
      renderBoardHighlight();
      renderBackgroundGrid();
      renderCards(visibleCourses);
      renderNowLine();
      renderLegend(visibleCourses);

      const hasCourses = visibleCourses.length > 0;
      emptyState.hidden = hasCourses;
      schedule.style.display = hasCourses ? 'grid' : 'none';
      legend.style.display = hasCourses ? 'flex' : 'none';
    };

    const shiftWeek = (offset) => {
      const currentIndex = allWeeks.indexOf(state.week);
      const nextIndex = Math.min(allWeeks.length - 1, Math.max(0, currentIndex + offset));
      state.week = allWeeks[nextIndex] || state.week;
      render();
    };

    document.getElementById('prevWeek').addEventListener('click', () => shiftWeek(-1));
    document.getElementById('nextWeek').addEventListener('click', () => shiftWeek(1));
    sheetOverlay.addEventListener('click', closeSheet);
    sheetClose.addEventListener('click', closeSheet);
    window.addEventListener('resize', render);

    render();
  </script>
</body>
</html>`;
};
