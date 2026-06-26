import {
  loadConfig,
  storageStatePath,
  examHtmlPath
} from "./config.js";
import { launchBrowserSession } from "./browser.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { openExamPage, saveSession } from "./exam-page.js";

const run = async (): Promise<void> => {
  logDivider("START EXAM FETCH");
  await ensureArtifactsDirectory();

  logStep("Loading .env configuration.");
  const config = loadConfig();

  const { browser, context } = await launchBrowserSession(config, storageStatePath);

  try {
    const page = await openExamPage(context, config);

    logStep("Saving authenticated session.");
    await saveSession(context, storageStatePath);

    logStep("Capturing exam page HTML.");
    const html = await page.content();
    await writeTextFile(examHtmlPath, html);

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
