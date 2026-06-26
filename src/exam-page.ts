import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { logStep } from "./logger.js";
import { looksLikeLoginPage, waitForManualLogin } from "./login.js";

const openPage = async (page: Page, url: string, label: string): Promise<void> => {
  logStep(`Opening ${label}: ${url}`);
  await page.goto(url, { waitUntil: "domcontentloaded" });
  await page.waitForLoadState("networkidle").catch(() => undefined);
};

const navigateToExamList = async (page: Page, config: AppConfig): Promise<void> => {
  await openPage(page, config.examQueryUrl, "exam query URL");

  // If we are on the login page, we can't proceed. Return and let the caller handle it.
  if (await looksLikeLoginPage(page)) {
    logStep("Redirected to login page. Aborting navigation to exam list.");
    return;
  }

  logStep("On exam query page. Selecting semester and then submitting form.");
  
  const semesterSelector = "select[name='xnxqid']";
  
  try {
    await page.waitForSelector(semesterSelector, { timeout: 5000 });
    
    if (config.semester) {
      logStep(`Found semester in config: ${config.semester}. Selecting it.`);
      await page.selectOption(semesterSelector, config.semester);
    }
  } catch (e) {
    logStep(`Could not find semester selector '${semesterSelector}'.`);
  }

  try {
    logStep("Submitting form via JavaScript evaluation.");
    await page.evaluate(() => {
      const form = document.querySelector<HTMLFormElement>('form[name="ksapQueryForm"]');
      if (form) {
        form.action = "/njlgdx/xsks/xsksap_list";
        form.submit();
      }
    });
  } catch (e) {
    logStep("JavaScript form submission failed. Falling back to button click.");
    await page.click('text="查询"');
  }
  
  try {
    logStep("Waiting for exam results table to appear after form submission...");
    await page.waitForSelector("#dataList", { timeout: 10000 });
    await page.waitForLoadState("networkidle").catch(() => undefined);
    logStep("Exam results table appeared successfully.");
  } catch (e) {
    logStep("Did not find exam results table.");
  }
};

export const openExamPage = async (
  context: BrowserContext,
  config: AppConfig
): Promise<Page> => {
  const page = await context.newPage();

  page.on("request", request => {
    console.log(">>", request.method(), request.url());
  });
  page.on("response", response => {
    console.log("<<", response.status(), response.url());
  });
  
  // Always start by attempting to navigate to the exam list via the query page.
  await navigateToExamList(page, config);

  // After attempting to navigate, check if we landed on a login page.
  if (await looksLikeLoginPage(page)) {
    logStep("Saved login state is unavailable or expired. Switching to manual login flow.");
    await openPage(page, config.loginUrl, "login URL");

    logStep("Calling waitForManualLogin. Please log in manually.");
    await waitForManualLogin(page, config);
    logStep("Returned from waitForManualLogin. Waiting for 2 seconds for login to settle.");
    await page.waitForTimeout(2000);

    logStep("Manual login completed. Re-opening exam query page with the fresh session.");
    await navigateToExamList(page, config);

    logStep("Checking if we are on a login page again after navigation...");
    if (await looksLikeLoginPage(page)) {
      logStep(`Still on a login page at ${page.url()}. Aborting.`);
      throw new Error("The site still redirects to the login page after manual sign-in.");
    }
    logStep("Login seems successful. Not on a login page.");
  } else {
    logStep("Finished navigating to exam page. Session was valid.");
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
