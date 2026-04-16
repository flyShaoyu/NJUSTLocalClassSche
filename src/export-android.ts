import path from "node:path";
import { access, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import {
  homeImageArtifactsDir,
  homeViewPath,
  timetableHtmlPath,
  timetableJsonPath,
  timetableViewPath
} from "./config.js";
import { logDivider, logStep } from "./logger.js";

const androidAssetsDir = path.resolve("android", "app", "src", "main", "assets");

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

const copyHomeGallery = async (): Promise<void> => {
  const targetDir = path.join(androidAssetsDir, "resources");
  await ensureDir(targetDir);
  const sourceExists = await exists(homeImageArtifactsDir);
  const targetEntries = await readdir(targetDir, { withFileTypes: true });

  await Promise.all(
    targetEntries
      .filter((entry) => entry.isFile())
      .map((entry) => rm(path.join(targetDir, entry.name), { force: true }))
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
    files: {
      timetableHtml: "timetable.html",
      timetableJson: "timetable.json",
      timetableView: "timetable-view.html",
      homeView: "home-view.html"
    }
  };

  await writeFile(metaPath, JSON.stringify(meta, null, 2), "utf8");
  logStep(`Wrote metadata file: ${metaPath}`);
};

const run = async (): Promise<void> => {
  logDivider("ANDROID EXPORT");
  await ensureDir(androidAssetsDir);

  await copyIfExists(timetableViewPath, path.join(androidAssetsDir, "timetable-view.html"));
  await copyIfExists(homeViewPath, path.join(androidAssetsDir, "home-view.html"));
  await copyIfExists(timetableJsonPath, path.join(androidAssetsDir, "timetable.json"));
  await copyIfExists(timetableHtmlPath, path.join(androidAssetsDir, "timetable.html"));
  await copyHomeGallery();
  await writeMetaFile();

  logStep(`Done. Android assets are ready in ${androidAssetsDir}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
