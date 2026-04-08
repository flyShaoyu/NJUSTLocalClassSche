import {
  loadConfig,
  storageStatePath,
  timetableHtmlPath,
  timetableJsonPath,
  timetableViewPath
} from "./config.js";
import { launchBrowserSession } from "./browser.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { parseTimetableHtml } from "./html-parser.js";
import { logDivider, logStep } from "./logger.js";
import { openTimetablePage, saveSession } from "./timetable-page.js";
import { renderTimetablePage } from "./timetable-ui.js";

const run = async (): Promise<void> => {
  logDivider("START");
  await ensureArtifactsDirectory();

  logStep("Loading .env configuration.");
  const config = loadConfig();

  const { browser, context } = await launchBrowserSession(config, storageStatePath);

  try {
    const page = await openTimetablePage(context, config);

    logStep("Saving authenticated session.");
    await saveSession(context, storageStatePath);

    logStep("Capturing timetable page HTML.");
    const html = await page.content();
    await writeTextFile(timetableHtmlPath, html);

    logStep("Parsing timetable data from saved HTML.");
    const courses = parseTimetableHtml(html);
    await writeTextFile(timetableJsonPath, JSON.stringify(courses, null, 2));
    await writeTextFile(timetableViewPath, renderTimetablePage(courses));

    logStep(`Done. HTML saved to ${timetableHtmlPath}`);
    logStep(`Done. JSON saved to ${timetableJsonPath}`);
    logStep(`Done. Frontend saved to ${timetableViewPath}`);
    logStep(`Done. Parsed ${courses.length} timetable entries.`);
  } finally {
    logStep("Closing browser.");
    await context.close();
    await browser.close();
    logDivider("END");
  }
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
