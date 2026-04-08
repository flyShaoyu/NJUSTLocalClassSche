import fs from "node:fs/promises";
import { timetableJsonPath, timetableViewPath } from "./config.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { logDivider, logStep } from "./logger.js";
import { renderTimetablePage } from "./timetable-ui.js";
import { TimetableCourse } from "./types.js";

const run = async (): Promise<void> => {
  logDivider("UI");
  await ensureArtifactsDirectory();

  logStep(`Reading timetable JSON from ${timetableJsonPath}`);
  const content = await fs.readFile(timetableJsonPath, "utf8");
  const courses = JSON.parse(content) as TimetableCourse[];

  logStep("Rendering timetable frontend.");
  const html = renderTimetablePage(courses);
  await writeTextFile(timetableViewPath, html);

  logStep(`Done. Frontend page saved to ${timetableViewPath}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
