import fs from "node:fs/promises";
import {
  examHtmlPath,
  examJsonPath,
  examViewPath,
  scoreHtmlPath,
  scoreJsonPath,
  timetableHtmlPath,
  timetableJsonPath,
  timetableViewPath
} from "./config.js";
import { ensureArtifactsDirectory, fileExists, writeTextFile } from "./fs-utils.js";
import { parseExamArrangementHtml } from "./exam-parser.js";
import { renderExamPage } from "./exam-ui.js";
import { parseTimetableHtml } from "./html-parser.js";
import { logDivider, logStep } from "./logger.js";
import { parseScoreHtml } from "./score-parser.js";
import { renderTimetablePage } from "./timetable-ui.js";

const run = async (): Promise<void> => {
  logDivider("PARSE");
  await ensureArtifactsDirectory();

  logStep(`Reading timetable HTML from ${timetableHtmlPath}`);
  const html = await fs.readFile(timetableHtmlPath, "utf8");

  const courses = parseTimetableHtml(html);
  await writeTextFile(timetableJsonPath, JSON.stringify(courses, null, 2));
  await writeTextFile(timetableViewPath, renderTimetablePage(courses));

  logStep(`Done. JSON saved to ${timetableJsonPath}`);
  logStep(`Done. Frontend saved to ${timetableViewPath}`);
  logStep(`Done. Parsed ${courses.length} timetable entries.`);

  if (await fileExists(examHtmlPath)) {
    logStep(`Reading exam arrangement HTML from ${examHtmlPath}`);
    const examHtml = await fs.readFile(examHtmlPath, "utf8");
    const exams = parseExamArrangementHtml(examHtml, courses);
    await writeTextFile(examJsonPath, JSON.stringify(exams, null, 2));
    await writeTextFile(examViewPath, renderExamPage(exams));
    logStep(`Done. Exam JSON saved to ${examJsonPath}`);
    logStep(`Done. Exam frontend saved to ${examViewPath}`);
    logStep(`Done. Parsed ${exams.length} exam arrangement entries.`);
  } else {
    logStep(`Exam arrangement HTML not found, skipping: ${examHtmlPath}`);
  }

  if (await fileExists(scoreHtmlPath)) {
    logStep(`Reading score HTML from ${scoreHtmlPath}`);
    const scoreHtml = await fs.readFile(scoreHtmlPath, "utf8");
    const scores = parseScoreHtml(scoreHtml);
    await writeTextFile(scoreJsonPath, JSON.stringify(scores, null, 2));
    logStep(`Done. Score JSON saved to ${scoreJsonPath}`);
    logStep(`Done. Parsed ${scores.length} score entries.`);
  } else {
    logStep(`Score HTML not found, skipping: ${scoreHtmlPath}`);
  }

  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
