import {
  levelExamHtmlPath,
  levelExamJsonPath,
  storageStatePath,
  loadConfig
} from "./config.js";
import { launchBrowserSession } from "./browser.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { saveSession } from "./timetable-page.js";
import { openLevelExamPage, readLevelExamPageHtml } from "./level-exam-page.js";
import { parseLevelExamHtml } from "./level-exam-parser.js";

const levelExamScreenshotPath = "artifacts/level-exam-list.png";

const run = async (): Promise<void> => {
  logDivider("START LEVEL EXAM FETCH");
  await ensureArtifactsDirectory();

  const config = loadConfig();
  const { browser, context } = await launchBrowserSession(config, storageStatePath);

  try {
    const page = await openLevelExamPage(context, config);
    await saveSession(context, storageStatePath);

    const currentUrl = page.url();
    const html = await readLevelExamPageHtml(page);
    await writeTextFile(levelExamHtmlPath, html);
    await page.screenshot({ path: levelExamScreenshotPath, fullPage: true });
    const records = parseLevelExamHtml(html);
    await writeTextFile(levelExamJsonPath, JSON.stringify(records, null, 2));

    logStep(`Done. Level exam HTML saved to ${levelExamHtmlPath}`);
    logStep(`Done. Level exam JSON saved to ${levelExamJsonPath}`);
    logStep(`Done. Level exam screenshot saved to ${levelExamScreenshotPath}`);
    logStep(`Current URL: ${currentUrl}`);
    logStep(`Parsed ${records.length} level exam entries.`);
  } finally {
    await context.close();
    await browser.close();
    logDivider("END LEVEL EXAM FETCH");
  }
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
