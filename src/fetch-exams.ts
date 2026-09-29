import {
  loadConfig,
  storageStatePath,
  examHtmlPath
} from "./config.js";
import { launchBrowserSession } from "./browser.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { openExamPage, saveSession } from "./exam-page.js";
import { withStep } from "./diagnostics.js";

const run = async (): Promise<void> => {
  logDivider("START EXAM FETCH");
  await ensureArtifactsDirectory();

  logStep("Loading .env configuration.");
  const config = loadConfig();

  const { browser, context } = await withStep("启动浏览器并读取登录状态", () => launchBrowserSession(config, storageStatePath));

  try {
    const page = await withStep("打开考试安排页面", () => openExamPage(context, config));

    logStep("Saving authenticated session.");
    await withStep("保存登录状态", () => saveSession(context, storageStatePath));

    logStep("Capturing exam page HTML.");
    const html = await withStep("读取考试安排网页", () => page.content());
    await withStep("保存考试安排原始网页", () => writeTextFile(examHtmlPath, html));

    logStep(`Done. Exam HTML saved to ${examHtmlPath}`);
  } finally {
    logStep("Closing browser.");
    await context.close();
    await browser.close();
    logDivider("END EXAM FETCH");
  }
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
