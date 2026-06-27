import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { logStep } from "./logger.js";
import { looksLikeLoginPage, waitForManualLogin } from "./login.js";

const openPage = async (page: Page, url: string, label: string): Promise<void> => {
  logStep(`Opening ${label}: ${url}`);
  await page.goto(url, { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle").catch(() => undefined);
};

const navigateToScorePage = async (page: Page, scoreUrl: string): Promise<void> => {
  await openPage(page, scoreUrl, "score URL");
};

export const openScorePage = async (
  context: BrowserContext,
  config: AppConfig
): Promise<Page> => {
  const page = await context.newPage();
  await navigateToScorePage(page, config.scoreUrl);

  if (await looksLikeLoginPage(page)) {
    logStep("Saved login state is unavailable or expired. Switching to manual login flow.");
    await openPage(page, config.loginUrl, "login URL");
    await waitForManualLogin(page, config);

    logStep("Manual login completed. Waiting for 2 seconds for login to settle.");
    await page.waitForTimeout(2000);

    logStep("Re-opening score page with the fresh session.");
    await navigateToScorePage(page, config.scoreUrl);

    if (await looksLikeLoginPage(page)) {
      throw new Error("The site still redirects to the login page after manual sign-in.");
    }
  } else {
    logStep("Existing storage state is valid. Reusing the saved login session.");
  }

  return page;
};

export const readScorePageHtml = async (page: Page): Promise<string> => {
  const currentUrl = page.url();
  logStep(`Fetching raw score page HTML with charset fallback decoding from ${currentUrl}`);

  return page.evaluate(async (url) => {
    const response = await fetch(url, { credentials: "include" });
    const buffer = await response.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    const utf8Decoded = new TextDecoder("utf-8").decode(bytes);
    if (!utf8Decoded.includes("\uFFFD")) {
      return utf8Decoded;
    }

    const encodings = ["gb18030", "gbk"];

    for (const encoding of encodings) {
      try {
        return new TextDecoder(encoding).decode(bytes);
      } catch {
        continue;
      }
    }

    return new TextDecoder().decode(bytes);
  }, currentUrl);
};
