import fs from "node:fs/promises";
import path from "node:path";
import {
  homeImageArtifactsDir,
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

const collectHomeImages = async (): Promise<Array<{ fileName: string; src: string }>> => {
  await fs.mkdir(homeImageSourceDir, { recursive: true });
  await fs.rm(homeImageArtifactsDir, { recursive: true, force: true });
  await fs.mkdir(homeImageArtifactsDir, { recursive: true });

  const entries = await fs.readdir(homeImageSourceDir, { withFileTypes: true });
  const imageEntries = entries.filter((entry) => entry.isFile() && imageExtensions.has(path.extname(entry.name).toLowerCase()));

  return Promise.all(
    imageEntries.map(async (entry, index) => {
      const sourcePath = path.join(homeImageSourceDir, entry.name);
      const extension = path.extname(entry.name).toLowerCase();
      const safeFileName = `image-${String(index + 1).padStart(3, "0")}${extension}`;
      const targetPath = path.join(homeImageArtifactsDir, safeFileName);
      await fs.copyFile(sourcePath, targetPath);

      return {
        fileName: entry.name,
        src: `./resources/${safeFileName}`
      };
    })
  );
};

const run = async (): Promise<void> => {
  logDivider("UI");
  await ensureArtifactsDirectory();

  logStep(`Reading timetable JSON from ${timetableJsonPath}`);
  const content = await fs.readFile(timetableJsonPath, "utf8");
  const courses = JSON.parse(content) as TimetableCourse[];
  const homeImages = await collectHomeImages();

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
