import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { TEACHING_ORIGIN, TEACHING_SSO_URL } from "./endpoints.js";
import { looksLikeLoginPage, waitForManualLogin } from "./login.js";
import { logStep } from "./logger.js";
import { describeRequestFailure } from "./diagnostics.js";

export const navigate = async (page: Page, url: string): Promise<void> => {
  let response;
  try {
    response = await page.goto(url, { waitUntil: "domcontentloaded" });
  } catch (error) {
    throw describeRequestFailure("打开网页", url, error);
  }
  if (!response || !response.ok()) {
    throw new Error(`网站返回错误：请求 ${url}，实际地址 ${page.url()}，HTTP ${response?.status() ?? "无响应"}。`);
  }
};

export const requirePageContent = async (page: Page, selector: string, expectedUrl?: string): Promise<void> => {
  const actualUrl = page.url();
  if (await looksLikeLoginPage(page)) {
    throw new Error(`登录状态失效：业务页跳到了登录表单（${actualUrl}）。请重新完成统一认证。`);
  }
  if (expectedUrl) {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    if (expected.origin !== actual.origin || expected.pathname !== actual.pathname) {
      throw new Error(`目标网站跳转错误：预期 ${expected.origin}${expected.pathname}，实际 ${actual.origin}${actual.pathname}。`);
    }
  }
  try {
    await page.locator(selector).first().waitFor({ state: "attached", timeout: 10000 });
  } catch (error) {
    throw new Error(`页面解析错误：${actualUrl} 缺少预期内容 ${selector}；网站结构可能已变化，原缓存未覆盖。`, { cause: error });
  }
};

export const openAuthenticatedPage = async (
  context: BrowserContext, config: AppConfig, url: string, selector: string
): Promise<Page> => {
  const page = await context.newPage();
  await navigate(page, url);
  if (await looksLikeLoginPage(page)) {
    const usesSchoolSso = new URL(url).origin === TEACHING_ORIGIN;
    if (usesSchoolSso) {
      // Exchange an existing IDS session for the teaching site's own cookies.
      await navigate(page, TEACHING_SSO_URL);
    }
    if (await looksLikeLoginPage(page)) {
      await navigate(page, config.loginUrl);
      if (await looksLikeLoginPage(page)) await waitForManualLogin(page, config);
      if (usesSchoolSso) await navigate(page, TEACHING_SSO_URL);
    }
    await navigate(page, url);
  }
  await requirePageContent(page, selector, url);
  logStep("Authenticated business page verified; reusing this browser context's cookies.");
  return page;
};
