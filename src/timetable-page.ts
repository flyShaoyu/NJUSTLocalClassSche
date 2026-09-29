import { BrowserContext, Page } from "playwright";
import { AppConfig } from "./types.js";
import { openAuthenticatedPage } from "./authenticated-page.js";
import { logStep } from "./logger.js";

export const openTimetablePage = (context: BrowserContext, config: AppConfig): Promise<Page> =>
  openAuthenticatedPage(context, config, config.timetableUrl, "#kbtable");

export const saveSession = async (context: BrowserContext, storageStatePath: string): Promise<void> => {
  logStep(`Saving session state to: ${storageStatePath}`);
  await context.storageState({ path: storageStatePath });
};
