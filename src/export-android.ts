import path from "node:path";
import { access, copyFile, mkdir, writeFile } from "node:fs/promises";
import { timetableHtmlPath, timetableJsonPath, timetableViewPath } from "./config.js";
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

  await copyFile(sourcePath, targetPath);
  logStep(`Copied: ${sourcePath} -> ${targetPath}`);
};

const writeMetaFile = async (): Promise<void> => {
  const metaPath = path.join(androidAssetsDir, "cache-meta.json");
  const meta = {
    exportedAt: new Date().toISOString(),
    files: {
      timetableHtml: "timetable.html",
      timetableJson: "timetable.json",
      timetableView: "timetable-view.html"
    }
  };

  await writeFile(metaPath, JSON.stringify(meta, null, 2), "utf8");
  logStep(`Wrote metadata file: ${metaPath}`);
};

const run = async (): Promise<void> => {
  logDivider("ANDROID EXPORT");
  await ensureDir(androidAssetsDir);

  await copyIfExists(timetableViewPath, path.join(androidAssetsDir, "timetable-view.html"));
  await copyIfExists(timetableJsonPath, path.join(androidAssetsDir, "timetable.json"));
  await copyIfExists(timetableHtmlPath, path.join(androidAssetsDir, "timetable.html"));
  await writeMetaFile();

  logStep(`Done. Android assets are ready in ${androidAssetsDir}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
