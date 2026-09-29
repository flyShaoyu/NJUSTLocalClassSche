import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { openAuthenticatedPage } from "./authenticated-page.js";
import { readRawBusinessHtml } from "./raw-page.js";

export const openScorePage = (context: BrowserContext, config: AppConfig): Promise<Page> =>
  openAuthenticatedPage(context, config, config.scoreUrl, "#dataList");

export const readScorePageHtml = (page: Page): Promise<string> => readRawBusinessHtml(page, "成绩");
