import { buildTimetablePageScript } from './timetable-ui-script.js';
import { TimetableCourse } from './types.js';

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
  ["#6e7fd8", "#5a69c2"],
  ["#ffb287", "#ef9367"],
  ["#7fd7d0", "#5dbbb4"],
  ["#b799f2", "#9b7ddb"],
  ["#f2c66d", "#dfaa47"],
  ["#84c3a4", "#68aa8a"],
  ["#f08ca4", "#d96c87"],
  ["#85aef7", "#668fe4"]
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
      --paper: #ffffff;
      --ink: #3f5675;
      --muted: #95a5ba;
      --line: rgba(111, 138, 168, 0.16);
      --axis-width: clamp(18px, 4.5vw, 28px);
      --row-min-height: 48px;
      --row-height: clamp(var(--row-min-height), 5.2vh, 68px);
      --cell-gap: 2px;
      --footer-height: 64px;
      --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
      --font-ui: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    }

    * { box-sizing: border-box; }

    html,
    body {
      height: 100%;
      overflow: hidden;
    }

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
      height: 100dvh;
      min-height: 100dvh;
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      padding-bottom: calc(var(--footer-height) + 8px);
      background: #ffffff;
    }

    .hero {
      padding: 18px 10px 12px;
      color: white;
      background: linear-gradient(180deg, rgba(121, 175, 230, 0.98), rgba(108, 167, 224, 0.96));
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
      justify-content: space-between;
    }

    .back-button,
    .icon-button {
      border: 0;
      color: white;
      cursor: pointer;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      text-decoration: none;
      font-size: 22px;
    }

    .back-button {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: rgba(255,255,255,0.16);
    }

    .icon-button {
      width: 28px;
      height: 28px;
      border-radius: 0;
      background: transparent;
      font-size: 24px;
      line-height: 1;
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
      display: grid;
      grid-template-rows: auto minmax(0, 1fr);
      background: #ffffff;
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
      border-radius: 0;
      background: linear-gradient(180deg, rgba(182, 232, 196, 0.72) 0%, rgba(212, 241, 221, 0.46) 34%, rgba(234, 248, 237, 0.18) 68%, rgba(255,255,255,0.02) 100%);
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
      min-height: 40px;
      padding: 3px 2px 5px;
      text-align: center;
    }

    .header-date {
      display: block;
      font-size: clamp(9px, 2vw, 12px);
      color: var(--muted);
      margin-bottom: 4px;
    }

    .header-weekday {
      display: block;
      font-family: var(--font-cn);
      font-size: clamp(12px, 2.8vw, 16px);
      color: #566c87;
    }

    .header-cell.today .header-date,
    .header-cell.today .header-weekday {
      color: #4d78b2;
      font-weight: 700;
    }

    .header-cell.today {
      background: rgba(196, 234, 206, 0.92);
    }

    .grid-scroll {
      position: relative;
      z-index: 1;
      min-height: 0;
      overflow: hidden;
      background: #ffffff;
    }

    .schedule {
      position: relative;
      width: 100%;
      display: grid;
      grid-template-columns: var(--axis-width) repeat(7, minmax(0, 1fr));
      grid-template-rows: repeat(14, var(--row-height));
      column-gap: var(--cell-gap);
      row-gap: var(--cell-gap);
      padding: 0 var(--cell-gap) 0 0;
      background: #ffffff;
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
      padding-top: 3px;
      color: #7f8da0;
      font-size: clamp(10px, 2.4vw, 13px);
      background: #ffffff;
      z-index: 1;
    }

    .slot-cell {
      background: #ffffff;
    }

    .schedule-column-highlight {
      position: absolute;
      z-index: 2;
      top: 0;
      bottom: 0;
      border-radius: 0;
      background: linear-gradient(180deg, rgba(136, 210, 156, 0.74) 0%, rgba(174, 228, 190, 0.48) 34%, rgba(221, 243, 228, 0.20) 72%, rgba(255,255,255,0.02) 100%);
      pointer-events: none;
    }

    .course-card {
      position: relative;
      z-index: 3;
      margin: 0;
      padding: 4px 3px;
      border-radius: 4px;
      color: white;
      display: grid;
      grid-template-rows: auto 1fr;
      align-items: stretch;
      align-self: stretch;
      box-shadow: 0 6px 14px rgba(77, 102, 144, 0.14);
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

    .course-top,
    .course-title,
    .course-bottom {
      position: relative;
      z-index: 1;
    }

    .course-top {
      font-size: clamp(8px, 1.95vw, 11px);
      line-height: 1.22;
      font-family: var(--font-ui);
      font-weight: 600;
      text-align: center;
      text-shadow: 0 1px 2px rgba(0,0,0,0.12);
    }

    .course-room {
      margin-top: 2px;
      opacity: 0.96;
      font-size: 1em;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: normal;
      word-break: break-all;
    }

    .session-divider {
      position: absolute;
      z-index: 2;
      height: 1px;
      border-radius: 999px;
      background: rgba(137, 164, 198, 0.42);
      pointer-events: none;
    }

    .course-title {
      margin: 2px 0 0;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      min-height: 0;
    }

    .course-title-text {
      font-family: var(--font-ui);
      font-size: clamp(9px, 2.1vw, 11px);
      font-weight: 600;
      line-height: 1.2;
      letter-spacing: 0;
      word-break: break-all;
      display: -webkit-box;
      -webkit-line-clamp: 4;
      -webkit-box-orient: vertical;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .course-bottom {
      display: none;
    }

    .course-tap {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 2px 5px;
      border-radius: 999px;
      background: rgba(255,255,255,0.18);
      box-shadow: inset 0 1px 0 rgba(255,255,255,0.16);
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
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
      box-shadow: 0 -10px 24px rgba(55, 89, 136, 0.16);
      border-top: 1px solid rgba(181, 201, 226, 0.55);
      z-index: 1000;
    }

    .footer-underlay {
      position: fixed;
      left: 0;
      right: 0;
      bottom: 0;
      height: calc(var(--footer-height) + env(safe-area-inset-bottom) + 12px);
      background: #ffffff;
      z-index: 998;
      pointer-events: none;
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
      gap: 8px;
      justify-self: center;
    }

    .footer-center .icon-button,
    .footer-center .toolbar-button {
      color: #ffffff;
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

    .focused-card-layer {
      position: fixed;
      inset: 0;
      pointer-events: none;
      z-index: 1090;
    }

    .focused-card-clone {
      position: fixed;
      margin: 0;
      pointer-events: none;
      z-index: 1091;
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
      font-size: clamp(12px, 3.1vw, 14px);
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
      font-size: clamp(18px, 5.2vw, 24px);
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
      left: 20px;
      top: 40%;
      width: fit-content;
      min-width: 0;
      min-height: 0;
      padding: 6px 4px;
      border-radius: 6px;
      background: rgba(246, 116, 154, 0.92);
      font-family: var(--font-cn);
      font-size: clamp(10px, 2.6vw, 12px);
      line-height: 1.1;
      display: flex;
      align-items: center;
      justify-content: center;
      text-align: center;
      writing-mode: vertical-rl;
      text-orientation: upright;
      box-shadow: 0 8px 16px rgba(79, 88, 124, 0.16);
      transform: translateY(-50%);
    }

    .sheet-meta {
      display: grid;
      gap: 10px;
      padding-left: 58px;
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
      font-size: clamp(12px, 4.6vw, 20px);
      line-height: 1.2;
    }

    .sheet-note {
      font-size: clamp(10px, 2.5vw, 13px);
      opacity: 0.96;
    }

    .sheet-close {
      border: 0;
      width: 40px;
      height: 32px;
      border-radius: 999px;
      background: transparent;
      color: white;
      font-size: 18px;
      font-weight: 700;
      letter-spacing: -1px;
      cursor: pointer;
      line-height: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      align-self: center;
      padding: 0;
      transform: translateY(-4px) scaleX(1.35);
    }

    @media (max-width: 720px) {
      body {
        min-height: 100dvh;
        overflow: hidden;
      }
      .app { height: 100dvh; min-height: 100dvh; padding-bottom: calc(var(--footer-height) + 2px); }
      .hero {
        padding: 10px 6px 6px;
        padding-inline: 6px;
      }
      .back-button {
        width: 34px;
        height: 34px;
        border-radius: 10px;
        font-size: 18px;
      }
      .icon-button {
        width: 24px;
        height: 24px;
        font-size: 20px;
      }
      .footer-bar { left: 0; right: 0; }
      .board { border-radius: 14px 14px 0 0; }
      :root {
        --axis-width: 16px;
        --row-min-height: 24px;
        --row-height: 32px;
        --cell-gap: 2px;
      }
      .header-cell {
        min-height: 32px;
        padding: 2px 1px 4px;
      }
      .header-date {
        font-size: 8px;
        margin-bottom: 2px;
      }
      .header-weekday {
        font-size: 11px;
      }
      .axis-cell {
        font-size: 8px;
      }
      .course-card {
        padding: 2px 2px 1px;
        border-radius: 3px;
      }
      .course-top {
        font-size: 10px;
        line-height: 1.20;
      }
      .course-room {
        display: block;
        font-size: 10px;
      }
      .course-title {
        margin-top: 1px;
      }
      .course-title-text {
        font-size: 10px;
        line-height: 1.18;
        -webkit-line-clamp: 4;
      }
      .legend {
        display: none !important;
      }
      .footer-bar {
        padding: 12px 14px calc(12px + env(safe-area-inset-bottom));
      }
      .footer-side,
      .footer-center {
        font-size: 13px;
      }
      .footer-center .icon-button,
      .footer-center .toolbar-button {
        color: #ffffff;
      }
      .sheet-topbar { padding-inline: 16px; }
      .sheet-title { padding-inline: 16px; }
      .sheet-body { padding-inline: 16px; }
      .sheet-tag { left: 10px; }
      .sheet-meta { padding-left: 48px; padding-right: 10px; }
    }
  </style>
</head>
<body>
  <div class="app">
    <header class="hero">
      <div class="hero-main">
        <a class="back-button" href="./home-view.html" aria-label="返回首页">‹</a>
        <div class="hero-title">课表查询</div>
        <div style="width:42px;height:42px;"></div>
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

  <div class="footer-underlay"></div>
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
  <div id="focusedCardLayer" class="focused-card-layer"></div>
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

    ${buildTimetablePageScript({
      coursesJson: serializeForScript(courses),
      weekdaysJson: serializeForScript(WEEKDAYS),
      periodSlotsJson: serializeForScript(PERIOD_SLOTS),
      paletteJson: serializeForScript(COLOR_PALETTE),
      anchorWeek: ANCHOR_WEEK,
      anchorMondayJson: serializeForScript(ANCHOR_MONDAY),
      initialWeek
    })}
</body>
</html>`;
};

