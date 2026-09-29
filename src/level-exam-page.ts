import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { openAuthenticatedPage } from "./authenticated-page.js";
import { readRawBusinessHtml } from "./raw-page.js";

export const openLevelExamPage = (context: BrowserContext, config: AppConfig): Promise<Page> =>
  openAuthenticatedPage(context, config, config.levelExamUrl, "#dataList");

export const readLevelExamPageHtml = (page: Page): Promise<string> => readRawBusinessHtml(page, "等级考试");
