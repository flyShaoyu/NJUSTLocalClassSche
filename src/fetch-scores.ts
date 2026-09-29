import {
  loadConfig,
  scoreHtmlPath,
  scoreJsonPath,
  storageStatePath
} from "./config.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { launchBrowserSession } from "./browser.js";
import { saveSession } from "./timetable-page.js";
import { openScorePage, readScorePageHtml } from "./score-page.js";
import { parseScoreHtml } from "./score-parser.js";
import { withStep } from "./diagnostics.js";

const scoreScreenshotPath = "artifacts/score-list.png";

const run = async (): Promise<void> => {
  logDivider("START SCORE FETCH");
  await ensureArtifactsDirectory();

  const config = loadConfig();
  const { browser, context } = await withStep("启动浏览器并读取登录状态", () => launchBrowserSession(config, storageStatePath));

  try {
    const page = await withStep("打开成绩页面", () => openScorePage(context, config));
    await withStep("保存登录状态", () => saveSession(context, storageStatePath));

    const currentUrl = page.url();
    const html = await withStep("读取成绩网页", () => readScorePageHtml(page));
    await withStep("保存成绩原始网页", () => writeTextFile(scoreHtmlPath, html));
    await withStep("保存成绩截图", () => page.screenshot({ path: scoreScreenshotPath, fullPage: true }));
    const scores = await withStep("解析成绩", () => parseScoreHtml(html));
    await withStep("保存成绩数据", () => writeTextFile(scoreJsonPath, JSON.stringify(scores, null, 2)));
    logStep(`Done. Score HTML saved to ${scoreHtmlPath}`);
    logStep(`Done. Score JSON saved to ${scoreJsonPath}`);
    logStep(`Done. Score screenshot saved to ${scoreScreenshotPath}`);
    logStep(`Current URL: ${currentUrl}`);
    logStep(`Parsed ${scores.length} score entries.`);
  } finally {
    await context.close();
    await browser.close();
    logDivider("END SCORE FETCH");
  }
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
