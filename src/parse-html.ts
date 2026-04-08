import fs from "node:fs/promises";
import { timetableHtmlPath, timetableJsonPath, timetableViewPath } from "./config.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { parseTimetableHtml } from "./html-parser.js";
import { logDivider, logStep } from "./logger.js";
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
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
