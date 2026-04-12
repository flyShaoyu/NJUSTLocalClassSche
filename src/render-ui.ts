import fs from "node:fs/promises";
import path from "node:path";
import {
  homeImageSourceDir,
  homeViewPath,
  timetableJsonPath,
  timetableViewPath
} from "./config.js";
import { ensureArtifactsDirectory, writeTextFile } from "./fs-utils.js";
import { renderHomePage } from "./home-page-ui.js";
import { logDivider, logStep } from "./logger.js";
import { renderTimetablePage } from "./timetable-ui.js";
import { TimetableCourse } from "./types.js";

const imageExtensions = new Set([".jpg", ".jpeg", ".png", ".webp", ".gif"]);

const collectHomeImages = async (): Promise<Array<{ fileName: string; relativePath: string }>> => {
  await fs.mkdir(homeImageSourceDir, { recursive: true });

  const entries = await fs.readdir(homeImageSourceDir, { withFileTypes: true });
  return entries
    .filter((entry) => entry.isFile() && imageExtensions.has(path.extname(entry.name).toLowerCase()))
    .map((entry) => ({
      fileName: entry.name,
      relativePath: `./home-gallery/${entry.name}`
    }));
};

const syncHomeImagesToArtifacts = async (): Promise<void> => {
  const targetDir = path.resolve("artifacts", "home-gallery");
  await fs.mkdir(targetDir, { recursive: true });

  const entries = await fs.readdir(homeImageSourceDir, { withFileTypes: true });
  const sourceFiles = entries
    .filter((entry) => entry.isFile() && imageExtensions.has(path.extname(entry.name).toLowerCase()))
    .map((entry) => entry.name);

  const existingTargetFiles = await fs.readdir(targetDir, { withFileTypes: true });
  await Promise.all(
    existingTargetFiles
      .filter((entry) => entry.isFile() && !sourceFiles.includes(entry.name))
      .map((entry) => fs.rm(path.join(targetDir, entry.name), { force: true }))
  );

  await Promise.all(
    sourceFiles.map((fileName) =>
      fs.copyFile(path.join(homeImageSourceDir, fileName), path.join(targetDir, fileName))
    )
  );
};

const run = async (): Promise<void> => {
  logDivider("UI");
  await ensureArtifactsDirectory();

  logStep(`Reading timetable JSON from ${timetableJsonPath}`);
  const content = await fs.readFile(timetableJsonPath, "utf8");
  const courses = JSON.parse(content) as TimetableCourse[];
  const homeImages = await collectHomeImages();
  await syncHomeImagesToArtifacts();

  logStep("Rendering timetable frontend.");
  await writeTextFile(timetableViewPath, renderTimetablePage(courses));

  logStep("Rendering home frontend.");
  await writeTextFile(homeViewPath, renderHomePage(courses, homeImages));

  logStep(`Done. Frontend page saved to ${timetableViewPath}`);
  logStep(`Done. Home page saved to ${homeViewPath}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
