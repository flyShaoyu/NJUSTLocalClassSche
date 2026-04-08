import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { logStep } from "./logger.js";
import { looksLikeLoginPage, waitForManualLogin } from "./login.js";

const openPage = async (page: Page, url: string, label: string): Promise<void> => {
  logStep(`Opening ${label}: ${url}`);
  await page.goto(url, { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle").catch(() => undefined);
};

const navigateToTimetable = async (page: Page, timetableUrl: string): Promise<void> => {
  await openPage(page, timetableUrl, "timetable URL");
};

export const openTimetablePage = async (
  context: BrowserContext,
  config: AppConfig
): Promise<Page> => {
  const page = await context.newPage();
  await navigateToTimetable(page, config.timetableUrl);

  if (await looksLikeLoginPage(page)) {
    logStep("Saved login state is unavailable or expired. Switching to manual login flow.");
    await openPage(page, config.loginUrl, "login URL");
    await waitForManualLogin(page, config);

    logStep("Manual login completed. Re-opening timetable page with the fresh session.");
    await navigateToTimetable(page, config.timetableUrl);

    if (await looksLikeLoginPage(page)) {
      throw new Error("The site still redirects to the login page after manual sign-in.");
    }
  } else {
    logStep("Existing storage state is valid. Reusing the saved login session.");
  }

  return page;
};

export const saveSession = async (
  context: BrowserContext,
  storageStatePath: string
): Promise<void> => {
  logStep(`Saving session state to: ${storageStatePath}`);
  await context.storageState({ path: storageStatePath });
};
