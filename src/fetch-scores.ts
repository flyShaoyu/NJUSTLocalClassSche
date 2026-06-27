import {
  loadConfig,
  scoreHtmlPath,
  scoreJsonPath,
  storageStatePath
} from "./config.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { launchBrowserSession } from "./browser.js";
import { looksLikeLoginPage, waitForManualLogin } from "./login.js";
import { saveSession } from "./timetable-page.js";
import { openScorePage, readScorePageHtml } from "./score-page.js";
import { parseScoreHtml } from "./score-parser.js";

const bootstrapLoginUrl = "http://202.119.81.113:8080";
const scoreScreenshotPath = "artifacts/score-list.png";

const run = async (): Promise<void> => {
  logDivider("START SCORE FETCH");
  await ensureArtifactsDirectory();

  const config = loadConfig();
  const { browser, context } = await launchBrowserSession(config, storageStatePath);

  try {
    const loginPage = await context.newPage();
    logStep(`Opening login bootstrap URL: ${bootstrapLoginUrl}`);
    await loginPage.goto(bootstrapLoginUrl, { waitUntil: "domcontentloaded" });
    await loginPage.waitForLoadState("networkidle").catch(() => undefined);

    if (await looksLikeLoginPage(loginPage)) {
      logStep("Completing the required 113:8080 login flow before opening the score page.");
      await waitForManualLogin(loginPage, config);
      await loginPage.waitForTimeout(2000);
    } else {
      logStep("Bootstrap page did not look like a login form. Reusing the existing session.");
    }

    await saveSession(context, storageStatePath);
    await loginPage.close();

    const page = await openScorePage(context, config);

    if (await looksLikeLoginPage(page)) {
      await page.screenshot({ path: scoreScreenshotPath, fullPage: true });
      const loginHtml = await readScorePageHtml(page);
      await writeTextFile(scoreHtmlPath, loginHtml);
      throw new Error(
        `The score page still redirects to login even after the 113:8080 bootstrap flow. Screenshot saved to ${scoreScreenshotPath}`
      );
    }

    const currentUrl = page.url();
    const html = await readScorePageHtml(page);
    await writeTextFile(scoreHtmlPath, html);
    await page.screenshot({ path: scoreScreenshotPath, fullPage: true });
    const scores = parseScoreHtml(html);
    await writeTextFile(scoreJsonPath, JSON.stringify(scores, null, 2));
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
