import { Browser, BrowserContext, chromium } from "playwright";
import { AppConfig } from "./types.js";
import { fileExists } from "./fs-utils.js";
import { logStep } from "./logger.js";

export interface BrowserSession {
  browser: Browser;
  context: BrowserContext;
}

export const launchBrowserSession = async (
  config: AppConfig,
  storageStatePath: string
): Promise<BrowserSession> => {
  const hasSavedSession = await fileExists(storageStatePath);
  logStep(`Saved storage state present: ${String(hasSavedSession)}`);

  if (config.headless) {
    logStep(
      "HEADLESS=true was requested, but this flow keeps the browser visible so manual login remains available when the session expires."
    );
  }

  logStep("Launching Chromium in headed mode for reusable manual-login support.");
  const browser = await chromium.launch({ headless: false });

  const context = await browser.newContext(
    hasSavedSession ? { storageState: storageStatePath } : undefined
  );

  return { browser, context };
};
