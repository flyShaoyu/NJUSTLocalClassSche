import path from "node:path";
import { access, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import {
  homeImageArtifactsDir,
  homeViewPath,
} from "./config.js";
import { renderExamPage } from "./exam-ui.js";
import { renderHomePage } from "./home-page-ui.js";
import { logDivider, logStep } from "./logger.js";
import { renderScorePage } from "./score-ui.js";
import { renderTimetablePage } from "./timetable-ui.js";

const androidAssetsDir = path.resolve("android", "app", "src", "main", "assets");
const bundledTemplateFiles = {
  timetableView: "timetable-view.html",
  examView: "exam-view.html",
  scoreView: "score-view.html",
  homeView: "home-view.html"
} as const;
const personalDataFiles = [
  "timetable.json",
  "exam-list.json",
  "score-list.json",
  "timetable.html"
] as const;

const ensureDir = async (dirPath: string): Promise<void> => {
  await mkdir(dirPath, { recursive: true });
};

const exists = async (filePath: string): Promise<boolean> => {
  try {
    await access(filePath);
    return true;
  } catch {
    return false;
  }
};

const copyIfExists = async (sourcePath: string, targetPath: string): Promise<void> => {
  if (!(await exists(sourcePath))) {
    logStep(`Skip missing file: ${sourcePath}`);
    return;
  }

  const content = await readFile(sourcePath);
  await writeFile(targetPath, content);

  const [sourceStat, targetStat] = await Promise.all([stat(sourcePath), stat(targetPath)]);
  if (sourceStat.size !== targetStat.size) {
    throw new Error(`Export verification failed for ${path.basename(targetPath)}: ${sourceStat.size} != ${targetStat.size}`);
  }

  logStep(`Copied: ${sourcePath} -> ${targetPath} (${targetStat.size} bytes)`);
};

const removeIfExists = async (targetPath: string): Promise<void> => {
  if (!(await exists(targetPath))) {
    return;
  }

  await rm(targetPath, { force: true });
  logStep(`Removed stale asset: ${targetPath}`);
};

const writeTextAsset = async (fileName: string, content: string): Promise<void> => {
  const targetPath = path.join(androidAssetsDir, fileName);
  await writeFile(targetPath, content, "utf8");
  logStep(`Wrote sanitized asset: ${targetPath}`);
};

const readBundledHomeImages = async (): Promise<Array<{ fileName: string; src: string; detailSrc: string; fullSrc: string }>> => {
  if (!(await exists(homeViewPath))) {
    logStep(`Home view artifact missing, skip bundled image manifest: ${homeViewPath}`);
    return [];
  }

  const html = await readFile(homeViewPath, "utf8");
  const match = /const images = (\[.*?\]);/s.exec(html);
  if (!match) {
    logStep("No home image manifest found in home-view artifact.");
    return [];
  }

  const parsed = JSON.parse(match[1] ?? "[]") as Array<{
    caption?: string;
    src?: string;
    detailSrc?: string;
    fullSrc?: string;
  }>;

  return parsed.map((item, index) => ({
    fileName: item.caption || `image-${String(index + 1).padStart(3, "0")}`,
    src: item.src || "",
    detailSrc: item.detailSrc || item.src || "",
    fullSrc: item.fullSrc || item.detailSrc || item.src || ""
  }));
};

const copyHomeGallery = async (): Promise<void> => {
  const targetDir = path.join(androidAssetsDir, "resources");
  await ensureDir(targetDir);
  const sourceExists = await exists(homeImageArtifactsDir);
  const targetEntries = await readdir(targetDir, { withFileTypes: true });

  await Promise.all(
    targetEntries
      .filter((entry) => entry.isFile())
      .map((entry) => rm(path.join(targetDir, entry.name), { force: true }).catch(() => undefined))
  );

  if (sourceExists) {
    const sourceEntries = await readdir(homeImageArtifactsDir, { withFileTypes: true });
    for (const entry of sourceEntries) {
      if (!entry.isFile()) continue;
      await copyIfExists(path.join(homeImageArtifactsDir, entry.name), path.join(targetDir, entry.name));
    }
  }

  logStep(`Synced home resource directory: ${targetDir}`);
};

const writeMetaFile = async (): Promise<void> => {
  const metaPath = path.join(androidAssetsDir, "cache-meta.json");
  const meta = {
    exportedAt: new Date().toISOString(),
    sanitized: true,
    files: {
      timetableView: bundledTemplateFiles.timetableView,
      examView: bundledTemplateFiles.examView,
      scoreView: bundledTemplateFiles.scoreView,
      homeView: bundledTemplateFiles.homeView
    }
  };

  await writeFile(metaPath, JSON.stringify(meta, null, 2), "utf8");
  logStep(`Wrote metadata file: ${metaPath}`);
};

const run = async (): Promise<void> => {
  logDivider("ANDROID EXPORT");
  await ensureDir(androidAssetsDir);
  const bundledHomeImages = await readBundledHomeImages();

  await writeTextAsset(bundledTemplateFiles.timetableView, renderTimetablePage([]));
  await writeTextAsset(bundledTemplateFiles.examView, renderExamPage([]));
  await writeTextAsset(bundledTemplateFiles.scoreView, renderScorePage([]));
  await writeTextAsset(bundledTemplateFiles.homeView, renderHomePage([], bundledHomeImages));

  for (const fileName of personalDataFiles) {
    await removeIfExists(path.join(androidAssetsDir, fileName));
  }

  await copyHomeGallery();
  await writeMetaFile();

  logStep(`Done. Android assets are sanitized and ready in ${androidAssetsDir}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
