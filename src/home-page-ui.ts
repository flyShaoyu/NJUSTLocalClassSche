import { buildHomePageScript } from "./home-page-ui-script.js";
import { TimetableCourse } from "./types.js";

interface HomeImageAsset {
  fileName: string;
  src: string;
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
  { key: "exam", label: "考试安排", enabled: false },
  { key: "score", label: "成绩查询", enabled: false },
  { key: "level", label: "等级考试", enabled: false },
  { key: "add", label: "添加课表", enabled: false },
  { key: "schedule", label: "课表查询", enabled: true },
  { key: "room", label: "空闲教室", enabled: false },
  { key: "site", label: "常用网站", enabled: false },
  { key: "refresh", label: "更新课表", enabled: false },
  { key: "library", label: "图书搜索", enabled: false },
  { key: "borrow", label: "借阅信息", enabled: false }
] as const;

const serializeForScript = (value: unknown): string =>
  JSON.stringify(value)
    .replace(/</g, "\\u003c")
    .replace(/>/g, "\\u003e")
    .replace(/&/g, "\\u0026")
    .replace(/\u2028/g, "\\u2028")
    .replace(/\u2029/g, "\\u2029")
    .replace(/[^\x20-\x7e]/g, (char) => `\\u${char.charCodeAt(0).toString(16).padStart(4, "0")}`);

export const renderHomePage = (
  courses: TimetableCourse[],
  images: HomeImageAsset[],
  timetablePageHref = "./timetable-view.html"
): string => {
  const displayImages = images.map((image) => ({
    ...image,
    caption: image.fileName.replace(/\.[^.]+$/, "")
  }));
  const firstImage = displayImages[0];

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover" />
  <title>&#39318;&#39029;</title>
  <style>
    :root {
      --sky: #72a8df;
      --sky-deep: #5d95d4;
      --paper: #f5f7fb;
      --card: rgba(255, 255, 255, 0.97);
      --line: rgba(146, 165, 190, 0.24);
      --ink: #75808e;
      --muted: #adb6c3;
      --accent: #d79761;
      --overlay: rgba(22, 32, 45, 0.78);
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

    a,
    button {
      font: inherit;
      color: inherit;
    }

    .app {
      min-height: 100vh;
    }

    .content {
      padding: 0 0 16px;
    }

    .feature,
    .menu-wrap,
    .section {
      background: var(--card);
      border-radius: 16px;
      border: 1px solid rgba(187, 200, 217, 0.3);
      box-shadow: 0 10px 24px rgba(96, 115, 145, 0.08);
      overflow: hidden;
      margin: 0 0 8px;
    }

    .feature {
      position: relative;
      min-height: 132px;
      margin-left: 0;
      margin-right: 0;
      border-radius: 0;
      background:
        linear-gradient(180deg, rgba(19, 36, 28, 0.08), rgba(14, 24, 20, 0.42)),
        linear-gradient(135deg, #536d35 0%, #283422 26%, #6e7f4e 42%, #c7c89c 54%, #bfd5df 64%, #788366 76%, #2e3927 100%);
    }

    .feature-shell {
      padding: 0;
      background: #fff;
    }

    .feature-dots {
      display: flex;
      justify-content: center;
      gap: 12px;
      padding: 6px 0 2px;
      background: #fff;
    }

    .feature.empty::before {
      content: "\\56fe\\7247\\6587\\4ef6\\5939\\4e3a\\7a7a";
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      color: rgba(255, 255, 255, 0.78);
      font-family: var(--font-cn);
      font-size: 24px;
      letter-spacing: 2px;
    }

    .feature img {
      width: 100%;
      height: 132px;
      object-fit: cover;
      display: block;
    }

    .feature-viewport {
      position: relative;
      overflow: hidden;
    }

    .feature-track {
      display: flex;
      width: 100%;
      transition: transform 220ms ease;
      touch-action: pan-y;
    }

    .feature-slide {
      position: relative;
      min-width: 100%;
      height: 132px;
      cursor: zoom-in;
    }

    .feature-slide::after {
      content: "";
      position: absolute;
      inset: 0;
      background: linear-gradient(180deg, rgba(10, 17, 14, 0.04), rgba(10, 17, 14, 0.34));
      pointer-events: none;
    }

    .feature-nav {
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      width: 28px;
      height: 28px;
      border: 0;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.72);
      color: #5b6878;
      display: grid;
      place-items: center;
      cursor: pointer;
      z-index: 2;
    }

    .feature-nav.prev {
      left: 8px;
    }

    .feature-nav.next {
      right: 8px;
    }

    .feature-caption {
      position: absolute;
      left: 16px;
      right: 16px;
      bottom: 14px;
      color: #fff;
      text-align: center;
      text-shadow: 0 2px 10px rgba(0, 0, 0, 0.36);
      font-family: var(--font-cn);
      font-size: 15px;
      z-index: 1;
    }

    .panel-shell {
      padding: 0 10px;
    }

    .menu-wrap {
      padding: 8px 8px 10px;
    }

    .menu {
      display: grid;
      grid-template-columns: repeat(5, minmax(0, 1fr));
      gap: 4px 2px;
    }

    .menu-item {
      border: 0;
      background: none;
      padding: 0 2px;
      text-decoration: none;
      display: grid;
      justify-items: center;
      gap: 3px;
      color: #9ca6b5;
    }

    .menu-item.disabled {
      opacity: 0.92;
    }

    .menu-icon {
      position: relative;
      width: 56px;
      height: 56px;
      display: grid;
      place-items: center;
    }

    .menu-icon::before {
      content: "";
      position: absolute;
      inset: 4px;
      border-radius: 18px;
      background:
        radial-gradient(circle at 72% 26%, rgba(165, 211, 255, 0.55), transparent 28%),
        radial-gradient(circle at 40% 74%, rgba(189, 222, 255, 0.45), transparent 24%);
    }

    .menu-item svg {
      width: 50px;
      height: 50px;
      stroke: currentColor;
      stroke-width: 2;
      fill: none;
      stroke-linecap: round;
      stroke-linejoin: round;
      position: relative;
      z-index: 1;
    }

    .menu-item.disabled .menu-icon::after {
      content: "";
      position: absolute;
      width: 38px;
      height: 2px;
      border-radius: 99px;
      background: rgba(211, 147, 108, 0.92);
      transform: rotate(-35deg);
      z-index: 2;
    }

    .menu-label {
      min-height: 28px;
      text-align: center;
      font-family: var(--font-cn);
      font-size: 11px;
      line-height: 1.2;
      color: #77818f;
    }

    .dots {
      display: flex;
      justify-content: center;
      gap: 12px;
      padding-top: 4px;
    }

    .dot {
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #c5c9d2;
    }

    .dot.active {
      background: #69acec;
    }

    .lightbox {
      position: fixed;
      inset: 0;
      background: var(--overlay);
      display: none;
      place-items: center;
      z-index: 20;
      padding: 10px 8px 16px;
    }

    .lightbox.show {
      display: grid;
    }

    .lightbox-inner {
      position: relative;
      width: 100%;
      height: 100%;
      display: grid;
      grid-template-rows: 1fr auto;
      gap: 12px;
    }

    .lightbox-stage {
      position: relative;
      overflow: visible;
      touch-action: none;
      display: grid;
      place-items: center;
      min-height: 0;
    }

    .lightbox img {
      display: block;
      width: auto;
      max-width: min(96vw, 1600px);
      max-height: min(82vh, 1600px);
      object-fit: contain;
      background: rgba(255, 255, 255, 0.08);
      transform-origin: center center;
      will-change: transform;
      touch-action: none;
    }

    .lightbox-nav {
      position: absolute;
      top: 50%;
      transform: translateY(-50%);
      width: 40px;
      height: 40px;
      border: 0;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.84);
      color: #506072;
      display: grid;
      place-items: center;
      z-index: 2;
      cursor: pointer;
      font-size: 22px;
      line-height: 1;
    }

    .lightbox-nav.prev {
      left: 8px;
    }

    .lightbox-nav.next {
      right: 8px;
    }

    .lightbox-caption {
      color: #f8fbff;
      text-align: center;
      font-family: var(--font-cn);
      font-size: 16px;
      padding: 0 44px;
    }

    .lightbox-close {
      position: absolute;
      top: 2px;
      right: 2px;
      width: 38px;
      height: 38px;
      border: 0;
      border-radius: 999px;
      background: rgba(255, 255, 255, 0.9);
      color: #586678;
      cursor: pointer;
      z-index: 3;
    }

    .section-head {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 12px 16px;
      border-bottom: 1px solid var(--line);
      color: var(--accent);
      font-family: var(--font-cn);
      font-size: 16px;
    }

    .recent {
      padding: 0 14px;
    }

    .recent-item {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 10px;
      align-items: center;
      padding: 12px 0;
      border-bottom: 1px dashed rgba(176, 188, 204, 0.4);
    }

    .recent-item:last-child {
      border-bottom: 0;
    }

    .badge {
      width: 52px;
      height: 52px;
      border-radius: 50%;
      display: grid;
      place-items: center;
      background: linear-gradient(180deg, #eef8de, #e0f1dc);
      color: #7f8979;
      font-family: var(--font-cn);
      font-size: 15px;
      border: 1px solid rgba(177, 209, 164, 0.42);
      box-shadow: 0 3px 8px rgba(120, 136, 108, 0.12);
    }

    .badge.alert {
      background: linear-gradient(180deg, #fde8e6, #f8d8d4);
      color: #b86f69;
      border-color: rgba(230, 156, 148, 0.5);
      box-shadow: 0 3px 8px rgba(194, 118, 109, 0.16);
    }

    .badge.today {
      background: linear-gradient(180deg, #fff3cf, #f7e39f);
      color: #a88539;
      border-color: rgba(226, 192, 104, 0.56);
      box-shadow: 0 3px 8px rgba(190, 157, 83, 0.14);
    }

    .recent-main {
      min-width: 0;
    }

    .recent-title {
      font-family: var(--font-cn);
      font-size: 16px;
      color: #59626f;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .recent-meta {
      margin-top: 4px;
      color: #b6bcc5;
      font-size: 11px;
    }

    .recent-room {
      font-family: var(--font-cn);
      font-size: 16px;
      color: #8f959c;
      white-space: nowrap;
    }

    .cta {
      display: block;
      text-align: center;
      text-decoration: none;
      padding: 14px 16px 16px;
      color: #7b8795;
      font-family: var(--font-cn);
      font-size: 16px;
      border-top: 1px solid var(--line);
      background:
        radial-gradient(circle at 85% 20%, rgba(187, 226, 255, 0.55), transparent 28%),
        linear-gradient(180deg, rgba(247, 250, 255, 0.96), rgba(234, 244, 255, 0.96));
    }

    .toast {
      position: fixed;
      left: 50%;
      bottom: 26px;
      transform: translate(-50%, 20px);
      max-width: calc(100vw - 32px);
      padding: 12px 16px;
      border-radius: 14px;
      background: rgba(70, 86, 107, 0.92);
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

    @media (min-width: 721px) {
      .app {
        max-width: 640px;
        margin: 0 auto;
      }
    }

    @media (max-width: 420px) {
      .menu-icon {
        width: 52px;
        height: 52px;
      }

      .menu-item svg {
        width: 46px;
        height: 46px;
      }

      .recent-room {
        font-size: 14px;
      }
    }
  </style>
</head>
<body>
  <div class="app">
    <main class="content">
      <div class="feature-shell">
        <section class="feature${firstImage ? "" : " empty"}">
          <div class="feature-viewport">
            <div id="featureTrack" class="feature-track"></div>
            <button id="featurePrev" class="feature-nav prev" type="button" aria-label="上一张">‹</button>
            <button id="featureNext" class="feature-nav next" type="button" aria-label="下一张">›</button>
          </div>
          <div id="featureCaption" class="feature-caption">${firstImage ? firstImage.caption : "校园风景图片占位"}</div>
        </section>
        <div class="feature-dots dots"></div>
      </div>

      <div class="panel-shell">
        <section class="menu-wrap">
          <div id="menuGrid" class="menu"></div>
        </section>

        <section class="section">
          <div class="section-head">
            <span>最近课表</span>
          </div>
          <div id="recentList" class="recent"></div>
          <a class="cta" href="${timetablePageHref}">查看完整课表</a>
        </section>
      </div>
    </main>
  </div>

  <div id="toast" class="toast"></div>
  <div id="lightbox" class="lightbox" aria-hidden="true">
    <div class="lightbox-inner">
      <button id="lightboxClose" class="lightbox-close" type="button" aria-label="关闭">×</button>
      <div class="lightbox-stage">
        <button id="lightboxPrev" class="lightbox-nav prev" type="button" aria-label="上一张">‹</button>
        <img id="lightboxImage" alt="" />
        <button id="lightboxNext" class="lightbox-nav next" type="button" aria-label="下一张">›</button>
      </div>
      <div id="lightboxCaption" class="lightbox-caption"></div>
    </div>
  </div>

  ${buildHomePageScript({
    coursesJson: serializeForScript(courses),
    imagesJson: serializeForScript(displayImages),
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
