import {
  examHtmlPath,
  examJsonPath,
  examViewPath,
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
import { openExamPage } from "./exam-page.js";
import { parseExamArrangementHtml } from "./exam-parser.js";
import { renderExamPage } from "./exam-ui.js";

const run = async (): Promise<void> => {
  logDivider("START");
  await ensureArtifactsDirectory();

  logStep("Loading .env configuration.");
  const config = loadConfig();

  const { browser, context } = await launchBrowserSession(config, storageStatePath);

  try {
    // --- Timetable ---
    logDivider("TIMETABLE");
    const timetablePage = await openTimetablePage(context, config);

    logStep("Saving authenticated session.");
    await saveSession(context, storageStatePath);

    logStep("Capturing timetable page HTML.");
    const timetableHtml = await timetablePage.content();
    await writeTextFile(timetableHtmlPath, timetableHtml);

    logStep("Parsing timetable data from saved HTML.");
    const courses = parseTimetableHtml(timetableHtml);
    await writeTextFile(timetableJsonPath, JSON.stringify(courses, null, 2));
    await writeTextFile(timetableViewPath, renderTimetablePage(courses));

    logStep(`Done. Timetable HTML saved to ${timetableHtmlPath}`);
    logStep(`Done. Timetable JSON saved to ${timetableJsonPath}`);
    logStep(`Done. Timetable View saved to ${timetableViewPath}`);
    logStep(`Parsed ${courses.length} timetable entries.`);

    // --- Exams ---
    logDivider("EXAMS");
    const examPage = await openExamPage(context, config);

    logStep("Capturing exam page HTML.");
    const examHtml = await examPage.content();
    await writeTextFile(examHtmlPath, examHtml);
    logStep(`Done. Exam HTML saved to ${examHtmlPath}`);

    logStep("Parsing exam data from saved HTML.");
    const exams = parseExamArrangementHtml(examHtml, courses);
    await writeTextFile(examJsonPath, JSON.stringify(exams, null, 2));
    await writeTextFile(examViewPath, renderExamPage(exams));

    logStep(`Done. Exam JSON saved to ${examJsonPath}`);
    logStep(`Done. Exam View saved to ${examViewPath}`);
    logStep(`Parsed ${exams.length} exam entries.`);
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
