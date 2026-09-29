import {
  examHtmlPath,
  examJsonPath,
  examViewPath,
  levelExamHtmlPath,
  levelExamJsonPath,
  levelExamViewPath,
  loadConfig,
  scoreHtmlPath,
  scoreJsonPath,
  scoreViewPath,
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
import { openScorePage, readScorePageHtml } from "./score-page.js";
import { parseScoreHtml } from "./score-parser.js";
import { renderScorePage } from "./score-ui.js";
import { openLevelExamPage, readLevelExamPageHtml } from "./level-exam-page.js";
import { parseLevelExamHtml } from "./level-exam-parser.js";
import { renderLevelExamPage } from "./level-exam-ui.js";
import { withStep } from "./diagnostics.js";

const run = async (): Promise<void> => {
  logDivider("START");
  await ensureArtifactsDirectory();

  logStep("Loading .env configuration.");
  const config = loadConfig();

  const { browser, context } = await withStep("启动浏览器并读取登录状态", () =>
    launchBrowserSession(config, storageStatePath));

  try {
    // --- Timetable ---
    logDivider("TIMETABLE");
    const timetablePage = await withStep("打开课表页面", () => openTimetablePage(context, config));

    logStep("Saving authenticated session.");
    await withStep("保存登录状态", () => saveSession(context, storageStatePath));

    logStep("Capturing timetable page HTML.");
    const timetableHtml = await withStep("读取课表网页", () => timetablePage.content());
    await withStep("保存课表原始网页", () => writeTextFile(timetableHtmlPath, timetableHtml));

    logStep("Parsing timetable data from saved HTML.");
    const courses = await withStep("解析课表", () => parseTimetableHtml(timetableHtml));
    await withStep("保存课表数据和页面", async () => {
      await writeTextFile(timetableJsonPath, JSON.stringify(courses, null, 2));
      await writeTextFile(timetableViewPath, renderTimetablePage(courses));
    });

    logStep(`Done. Timetable HTML saved to ${timetableHtmlPath}`);
    logStep(`Done. Timetable JSON saved to ${timetableJsonPath}`);
    logStep(`Done. Timetable View saved to ${timetableViewPath}`);
    logStep(`Parsed ${courses.length} timetable entries.`);

    // --- Exams ---
    logDivider("EXAMS");
    const examPage = await withStep("打开考试安排页面", () => openExamPage(context, config));

    logStep("Capturing exam page HTML.");
    const examHtml = await withStep("读取考试安排网页", () => examPage.content());
    await withStep("保存考试安排原始网页", () => writeTextFile(examHtmlPath, examHtml));
    logStep(`Done. Exam HTML saved to ${examHtmlPath}`);

    logStep("Parsing exam data from saved HTML.");
    const exams = await withStep("解析考试安排", () => parseExamArrangementHtml(examHtml, courses));
    await withStep("保存考试安排数据和页面", async () => {
      await writeTextFile(examJsonPath, JSON.stringify(exams, null, 2));
      await writeTextFile(examViewPath, renderExamPage(exams));
    });

    logStep(`Done. Exam JSON saved to ${examJsonPath}`);
    logStep(`Done. Exam View saved to ${examViewPath}`);
    logStep(`Parsed ${exams.length} exam entries.`);

    // --- Scores ---
    logDivider("SCORES");
    const scorePage = await withStep("打开成绩页面", () => openScorePage(context, config));

    logStep("Capturing score page HTML.");
    const scoreHtml = await withStep("读取成绩网页", () => readScorePageHtml(scorePage));
    await withStep("保存成绩原始网页", () => writeTextFile(scoreHtmlPath, scoreHtml));
    logStep(`Done. Score HTML saved to ${scoreHtmlPath}`);

    logStep("Parsing score data from saved HTML.");
    const scores = await withStep("解析成绩", () => parseScoreHtml(scoreHtml));
    await withStep("保存成绩数据和页面", async () => {
      await writeTextFile(scoreJsonPath, JSON.stringify(scores, null, 2));
      await writeTextFile(scoreViewPath, renderScorePage(scores));
    });

    logStep(`Done. Score JSON saved to ${scoreJsonPath}`);
    logStep(`Done. Score View saved to ${scoreViewPath}`);
    logStep(`Parsed ${scores.length} score entries.`);

    // --- Level Exams ---
    logDivider("LEVEL EXAMS");
    const levelExamPage = await withStep("打开等级考试页面", () => openLevelExamPage(context, config));

    logStep("Capturing level exam page HTML.");
    const levelExamHtml = await withStep("读取等级考试网页", () => readLevelExamPageHtml(levelExamPage));
    await withStep("保存等级考试原始网页", () => writeTextFile(levelExamHtmlPath, levelExamHtml));
    logStep(`Done. Level exam HTML saved to ${levelExamHtmlPath}`);

    logStep("Parsing level exam data from saved HTML.");
    const levelExams = await withStep("解析等级考试", () => parseLevelExamHtml(levelExamHtml));
    await withStep("保存等级考试数据和页面", async () => {
      await writeTextFile(levelExamJsonPath, JSON.stringify(levelExams, null, 2));
      await writeTextFile(levelExamViewPath, renderLevelExamPage(levelExams));
      await writeTextFile(scoreViewPath, renderScorePage(scores, levelExams));
    });

    logStep(`Done. Level exam JSON saved to ${levelExamJsonPath}`);
    logStep(`Done. Level exam View saved to ${levelExamViewPath}`);
    logStep(`Parsed ${levelExams.length} level exam entries.`);
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
