import path from "node:path";
import { buildHomePageScript } from "./home-ui-script.js";
import { TimetableCourse } from "./types.js";

interface HomeImageAsset {
  fileName: string;
  relativePath: string;
}

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

const MENU_ITEMS = [
  { key: "exam", label: "考试安排", icon: "📝", enabled: false },
  { key: "score", label: "成绩查询", icon: "📊", enabled: false },
  { key: "level", label: "等级考试", icon: "📄", enabled: false },
  { key: "add", label: "添加课表", icon: "☰", enabled: false },
  { key: "schedule", label: "课表查询", icon: "🗓", enabled: true },
  { key: "room", label: "空闲教室", icon: "🏛", enabled: false },
  { key: "site", label: "常用网站", icon: "🪐", enabled: false },
  { key: "refresh", label: "更新课表", icon: "⟳", enabled: false },
  { key: "library", label: "图书搜索", icon: "🔎", enabled: false },
  { key: "borrow", label: "借阅信息", icon: "▣", enabled: false }
] as const;

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029");

export const renderHomePage = (
  courses: TimetableCourse[],
  images: HomeImageAsset[],
  timetablePageHref = "./timetable-view.html"
): string => {
  const displayImages = images.map((image) => ({
    ...image,
    relativePath: image.relativePath.split(path.sep).join("/")
  }));
  const firstImage = displayImages[0];

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <title>鍛ㄤ笁璇捐〃</title>
  <style>
    :root {
      --sky: #73a8df;
      --sky-deep: #5f97d6;
      --paper: #f4f7fb;
      --card: rgba(255,255,255,0.96);
      --ink: #59697b;
      --muted: #9aa6b7;
      --line: rgba(126, 151, 184, 0.18);
      --accent: #d59a63;
      --nav-height: 88px;
      --font-cn: "STKaiti", "KaiTi", "Noto Serif SC", serif;
      --font-ui: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
    }

    * { box-sizing: border-box; }

    html,
    body {
      margin: 0;
      min-height: 100%;
      background:
        radial-gradient(circle at top left, rgba(255,255,255,0.42), transparent 20%),
        linear-gradient(180deg, #8bb8eb 0, var(--sky) 220px, var(--paper) 220px);
      color: var(--ink);
      font-family: var(--font-ui);
    }

    body {
      padding-bottom: calc(var(--nav-height) + env(safe-area-inset-bottom));
    }

    button,
    a {
      font: inherit;
    }

    .app {
      max-width: 960px;
      margin: 0 auto;
      min-height: 100vh;
    }

    .screen {
      display: none;
      min-height: 100vh;
    }

    .screen.active {
      display: block;
    }

    .topbar {
      position: sticky;
      top: 0;
      z-index: 10;
      padding: calc(16px + env(safe-area-inset-top)) 18px 16px;
      color: #fff;
      background: linear-gradient(180deg, rgba(119,170,224,0.98), rgba(106,158,214,0.96));
      backdrop-filter: blur(10px);
    }

    .topbar-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
    }

    .topbar-side {
      width: 54px;
      display: flex;
      justify-content: center;
    }

    .title {
      flex: 1;
      text-align: center;
      font-family: var(--font-cn);
      font-size: clamp(26px, 6vw, 34px);
      letter-spacing: 2px;
    }

    .bubble,
    .menu-item,
    .cta {
      border: 0;
      background: none;
      cursor: pointer;
    }

    .bubble {
      width: 54px;
      height: 54px;
      border-radius: 18px;
      background: rgba(255,255,255,0.18);
      color: #fff;
      font-size: 22px;
      display: grid;
      place-items: center;
      text-decoration: none;
    }

    .content {
      padding: 14px 14px 0;
    }

    .notice,
    .feature,
    .menu-wrap,
    .section {
      background: var(--card);
      border: 1px solid rgba(180, 196, 216, 0.18);
      box-shadow: 0 14px 32px rgba(93, 116, 150, 0.10);
    }

    .notice {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 12px 14px;
      margin-bottom: 12px;
      border-radius: 18px;
      color: var(--accent);
      font-family: var(--font-cn);
      font-size: 15px;
    }

    .feature {
      position: relative;
      min-height: 184px;
      margin-bottom: 12px;
      border-radius: 22px;
      overflow: hidden;
      background:
        linear-gradient(180deg, rgba(22,39,36,0.14), rgba(14,25,22,0.48)),
        linear-gradient(140deg, #4c6b3d 0%, #2d3d28 28%, #617552 42%, #60816b 48%, #dfe8d3 52%, #8db1be 60%, #2d3c2d 100%);
    }

    .feature.empty::before {
      content: "鍥剧墖鏂囦欢澶逛负绌?;
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      color: rgba(255,255,255,0.72);
      font-family: var(--font-cn);
      font-size: 24px;
      letter-spacing: 2px;
    }

    .feature img {
      width: 100%;
      height: 100%;
      min-height: 184px;
      object-fit: cover;
      display: block;
    }

    .feature-caption {
      position: absolute;
      left: 18px;
      right: 18px;
      bottom: 14px;
      color: #fff;
      text-align: center;
      text-shadow: 0 3px 10px rgba(0,0,0,0.28);
      font-family: var(--font-cn);
      font-size: clamp(20px, 5vw, 30px);
    }

    .menu-wrap {
      border-radius: 22px;
      padding: 14px 10px 18px;
      margin-bottom: 14px;
    }

    .menu {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 12px 4px;
    }

    .menu-item {
      display: grid;
      justify-items: center;
      gap: 8px;
      padding: 8px 2px;
      color: var(--ink);
      text-decoration: none;
    }

    .menu-item.disabled {
      opacity: 0.64;
    }

    .menu-icon {
      position: relative;
      width: 58px;
      height: 58px;
      border-radius: 18px;
      border: 2px solid rgba(178, 188, 206, 0.95);
      color: #a7b0c0;
      display: grid;
      place-items: center;
      font-size: 32px;
    }

    .menu-item.disabled .menu-icon::after {
      content: "";
      position: absolute;
      width: 46px;
      height: 2px;
      background: rgba(214, 143, 112, 0.86);
      border-radius: 999px;
      transform: rotate(-35deg);
    }

    .menu-label {
      min-height: 32px;
      text-align: center;
      font-family: var(--font-cn);
      font-size: 13px;
      line-height: 1.2;
    }

    .dots {
      display: flex;
      justify-content: center;
      gap: 10px;
      padding-top: 12px;
    }

    .dot {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #c2c8d3;
    }

    .dot.active {
      background: #6ca9eb;
    }

    .section {
      border-radius: 22px;
      overflow: hidden;
      margin-bottom: 14px;
    }

    .section-head {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 16px 18px;
      border-bottom: 1px solid var(--line);
      color: var(--accent);
      font-family: var(--font-cn);
      font-size: clamp(18px, 5vw, 28px);
    }

    .recent {
      padding: 0 14px 8px;
    }

    .recent-item {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 12px;
      align-items: center;
      padding: 16px 4px;
      border-bottom: 1px dashed rgba(161, 176, 196, 0.30);
    }

    .recent-item:last-child {
      border-bottom: 0;
    }

    .badge {
      width: 62px;
      height: 62px;
      border-radius: 50%;
      background: linear-gradient(180deg, #ecf6df, #dcf0da);
      border: 1px solid rgba(150, 197, 151, 0.32);
      display: grid;
      place-items: center;
      color: #7e8872;
      font-family: var(--font-cn);
      font-size: 18px;
    }

    .recent-main {
      min-width: 0;
    }

    .recent-title {
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
      font-family: var(--font-cn);
      font-size: clamp(18px, 5vw, 24px);
      color: #5a6370;
    }

    .recent-meta {
      margin-top: 6px;
      color: #b4b9c1;
      font-size: 14px;
    }

    .recent-room {
      white-space: nowrap;
      color: #8b8f97;
      font-family: var(--font-cn);
      font-size: clamp(22px, 6vw, 34px);
    }

    .cta {
      width: 100%;
      padding: 18px 16px 20px;
      border-top: 1px solid var(--line);
      color: #7d8794;
      font-family: var(--font-cn);
      font-size: clamp(20px, 5.2vw, 28px);
      text-decoration: none;
      display: block;
      text-align: center;
      background: linear-gradient(180deg, rgba(240,247,255,0.96), rgba(228,241,255,0.96));
    }

    .toast {
      position: fixed;
      left: 50%;
      bottom: 24px;
      transform: translate(-50%, 20px);
      max-width: calc(100vw - 32px);
      padding: 12px 16px;
      border-radius: 14px;
      background: rgba(71, 87, 108, 0.90);
      color: #fff;
      font-size: 14px;
      opacity: 0;
      pointer-events: none;
      transition: opacity 180ms ease, transform 180ms ease;
    }

    .toast.show {
      opacity: 1;
      transform: translate(-50%, 0);
    }

    @media (max-width: 720px) {
      :root {
        --nav-height: 82px;
      }

      .menu-icon {
        width: 52px;
        height: 52px;
        font-size: 28px;
      }

      .menu-label {
        font-size: 12px;
      }
    }
  </style>
</head>
<body>
  <div class="app">
    <section id="homeScreen" class="screen active">
      <header class="topbar">
        <div class="topbar-row">
          <div class="topbar-side"></div>
          <div id="homeTitle" class="title"></div>
          <div class="topbar-side">
            <button type="button" class="bubble" data-action="menu" aria-label="鏇村">鈼?/button>
          </div>
        </div>
      </header>

      <main class="content">
        <section class="notice">
          <span style="color:#b7c4d5;font-size:22px;">馃攬</span>
          <span id="noticeText">鏈湴璇捐〃宸插姞杞斤紝鍙煡鐪嬫渶杩戣绋嬩笌瀹屾暣璇捐〃銆?/span>
        </section>

        <section id="featureCard" class="feature${firstImage ? "" : " empty"}">
          ${
            firstImage
              ? `<img src="${firstImage.relativePath}" alt="${firstImage.fileName}" />`
              : ""
          }
          <div class="feature-caption">${firstImage ? firstImage.fileName : "椋庢櫙鍥剧墖鍗犱綅"}</div>
        </section>

        <section class="menu-wrap">
          <div id="menuGrid" class="menu"></div>
          <div class="dots">
            <span class="dot active"></span>
            <span class="dot"></span>
          </div>
        </section>

        <section class="section">
          <div class="section-head">
            <span style="color:#b7c4d5;">鈻?/span>
            <span>鏈€杩戣琛?/span>
          </div>
          <div id="recentList" class="recent"></div>
          <a class="cta" href="${timetablePageHref}">鏌ョ湅瀹屾暣璇捐〃</a>
        </section>

      </main>
    </section>
  </div>

  <div id="toast" class="toast"></div>

    ${buildHomePageScript({
      coursesJson: serializeForScript(courses),
      weekdaysJson: serializeForScript(WEEKDAYS),
      periodSlotsJson: serializeForScript(PERIOD_SLOTS),
      menuItemsJson: serializeForScript(MENU_ITEMS),
      timetablePageHrefJson: serializeForScript(timetablePageHref),
      anchorWeek: ANCHOR_WEEK,
      anchorMondayJson: serializeForScript(ANCHOR_MONDAY + "T00:00:00")
    })}
</body>
</html>`;
};


