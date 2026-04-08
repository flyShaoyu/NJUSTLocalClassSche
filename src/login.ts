import { Page } from "playwright";
import { AppConfig } from "./types.js";
import { logStep } from "./logger.js";

const DEFAULT_USERNAME_SELECTORS = [
  "#xh",
  "#username",
  "input[name='USERNAME']",
  "input[name='username']",
  "input[type='text']"
];

const DEFAULT_PASSWORD_SELECTORS = [
  "#pwd",
  "#password",
  "input[name='PASSWORD']",
  "input[name='password']",
  "input[type='password']"
];

const DEFAULT_SUCCESS_SELECTORS = [
  "text=课表",
  "text=退出",
  "text=安全退出",
  "text=学生课表",
  "text=个人课表"
];

const buildSelectorList = (
  preferred: string | undefined,
  fallbackSelectors: string[]
): string[] => (preferred ? [preferred, ...fallbackSelectors] : fallbackSelectors);

const firstVisibleSelector = async (
  page: Page,
  selectors: string[]
): Promise<string | undefined> => {
  for (const selector of selectors) {
    try {
      await page.locator(selector).first().waitFor({ state: "visible", timeout: 500 });
      return selector;
    } catch {
      continue;
    }
  }

  return undefined;
};

const prefillSavedCredentials = async (page: Page, config: AppConfig): Promise<void> => {
  if (!config.username && !config.password) {
    logStep("No saved username/password found in .env. Waiting for full manual login.");
    return;
  }

  const usernameSelector = await firstVisibleSelector(page, DEFAULT_USERNAME_SELECTORS);
  const passwordSelector = await firstVisibleSelector(page, DEFAULT_PASSWORD_SELECTORS);

  if (config.username && usernameSelector) {
    logStep(`Prefilling username input: ${usernameSelector}`);
    await page.locator(usernameSelector).first().fill(config.username);
  }

  if (config.password && passwordSelector) {
    logStep(`Prefilling password input: ${passwordSelector}`);
    await page.locator(passwordSelector).first().fill(config.password);
  }
};

export const looksLikeLoginPage = async (page: Page): Promise<boolean> => {
  const selector = await firstVisibleSelector(page, DEFAULT_PASSWORD_SELECTORS);
  logStep(`Login page detection result: ${selector ? `matched ${selector}` : "not matched"}`);
  return Boolean(selector);
};

const hasSuccessIndicator = async (
  page: Page,
  selectors: string[]
): Promise<string | undefined> => {
  for (const selector of selectors) {
    try {
      const locator = page.locator(selector).first();
      if (await locator.isVisible({ timeout: 200 })) {
        return selector;
      }
    } catch {
      continue;
    }
  }

  return undefined;
};

export const waitForManualLogin = async (page: Page, config: AppConfig): Promise<void> => {
  logStep("Session is missing or expired. Please complete login manually in the opened browser window.");
  await prefillSavedCredentials(page, config);
  logStep("Enter the captcha in the page, adjust credentials if needed, then submit the form.");

  const successSelectors = buildSelectorList(config.loginSuccessSelector, DEFAULT_SUCCESS_SELECTORS);
  const deadline = Date.now() + config.manualLoginTimeoutMs;

  while (Date.now() < deadline) {
    await page.waitForLoadState("domcontentloaded").catch(() => undefined);

    const successSelector = await hasSuccessIndicator(page, successSelectors);
    if (successSelector) {
      logStep(`Manual login success indicator matched: ${successSelector}`);
      return;
    }

    if (!(await looksLikeLoginPage(page))) {
      logStep("The login form is no longer visible. Treating manual login as complete.");
      return;
    }

    await page.waitForTimeout(1000);
  }

  throw new Error(
    `Manual login did not complete within ${config.manualLoginTimeoutMs} ms. Please try again.`
  );
};
