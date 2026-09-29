import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { openAuthenticatedPage, requirePageContent } from "./authenticated-page.js";
import { describeRequestFailure } from "./diagnostics.js";
export { saveSession } from "./timetable-page.js";

export const openExamPage = async (context: BrowserContext, config: AppConfig): Promise<Page> => {
  const page = await openAuthenticatedPage(context, config, config.examQueryUrl, "form[name='ksapQueryForm']");
  if (config.semester) await page.selectOption("select[name='xnxqid']", config.semester);
  let navigation;
  try {
    [navigation] = await Promise.all([
      page.waitForNavigation({ waitUntil: "domcontentloaded" }),
      page.locator("form[name='ksapQueryForm']").evaluate((element, listUrl) => {
      const form = element as HTMLFormElement;
      form.action = listUrl;
      form.submit();
      }, config.examListUrl)
    ]);
  } catch (error) {
    throw describeRequestFailure("提交考试查询", config.examListUrl, error);
  }
  if (!navigation || !navigation.ok()) {
    throw new Error(`考试查询网站返回错误：HTTP ${navigation?.status() ?? "无响应"}，地址 ${page.url()}。`);
  }
  await requirePageContent(page, "#dataList", config.examListUrl);
  return page;
};
