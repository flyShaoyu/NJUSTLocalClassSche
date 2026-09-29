import path from "node:path";
import { createHash } from "node:crypto";
import { access, mkdir, readdir, readFile, rm, stat, writeFile } from "node:fs/promises";
import {
  homeImageArtifactsDir,
  homeViewPath,
} from "./config.js";
import { renderExamPage } from "./exam-ui.js";
import { renderHomePage } from "./home-page-ui.js";
import { renderLevelExamPage } from "./level-exam-ui.js";
import { logDivider, logStep } from "./logger.js";
import { parseJsonStep, withStep } from "./diagnostics.js";
import { renderScorePage } from "./score-ui.js";
import { renderTimetablePage } from "./timetable-ui.js";

const androidAssetsDir = path.resolve("android", "app", "src", "main", "assets");
const androidBuildGradlePath = path.resolve("android", "app", "build.gradle.kts");
const updateExportDir = path.resolve("artifacts", "android-update");
const updatePackageRootDir = path.join(updateExportDir, "package");
const updateZipFileName = "classsche-assets.zip";
const updateZipPath = path.join(updateExportDir, updateZipFileName);
const updateManifestPath = path.join(updateExportDir, "manifest.json");
const bundledTemplateFiles = {
  timetableView: "timetable-view.html",
  examView: "exam-view.html",
  scoreView: "score-view.html",
  levelExamView: "level-exam-view.html",
  homeView: "home-view.html"
} as const;
const personalDataFiles = [
  "timetable.json",
  "exam-list.json",
  "score-list.json",
  "level-exam-list.json",
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

const writeTextPackageAsset = async (fileName: string, content: string): Promise<void> => {
  const targetPath = path.join(updatePackageRootDir, fileName);
  await ensureDir(path.dirname(targetPath));
  await writeFile(targetPath, content, "utf8");
};

const writeTextRuntimeAsset = async (fileName: string, content: string): Promise<void> => {
  await writeTextAsset(fileName, content);
  await writeTextPackageAsset(fileName, content);
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

  const parsed = parseJsonStep<Array<{
    caption?: string;
    src?: string;
    detailSrc?: string;
    fullSrc?: string;
  }>>("首页图片清单", homeViewPath, match[1] ?? "[]");

  return parsed.map((item, index) => ({
    fileName: item.caption || `image-${String(index + 1).padStart(3, "0")}`,
    src: item.src || "",
    detailSrc: item.detailSrc || item.src || "",
    fullSrc: item.fullSrc || item.detailSrc || item.src || ""
  }));
};

const copyHomeGallery = async (): Promise<void> => {
  const targetDir = path.join(androidAssetsDir, "resources");
  const packageTargetDir = path.join(updatePackageRootDir, "resources");
  await ensureDir(targetDir);
  await ensureDir(packageTargetDir);
  const sourceExists = await exists(homeImageArtifactsDir);
  const targetEntries = await readdir(targetDir, { withFileTypes: true });
  const packageTargetEntries = await readdir(packageTargetDir, { withFileTypes: true });

  await Promise.all(
    targetEntries
      .filter((entry) => entry.isFile())
      .map((entry) => rm(path.join(targetDir, entry.name), { force: true }))
  );
  await Promise.all(
    packageTargetEntries
      .filter((entry) => entry.isFile())
      .map((entry) => rm(path.join(packageTargetDir, entry.name), { force: true }))
  );

  if (sourceExists) {
    const sourceEntries = await readdir(homeImageArtifactsDir, { withFileTypes: true });
    for (const entry of sourceEntries) {
      if (!entry.isFile()) continue;
      await copyIfExists(path.join(homeImageArtifactsDir, entry.name), path.join(targetDir, entry.name));
      await copyIfExists(path.join(homeImageArtifactsDir, entry.name), path.join(packageTargetDir, entry.name));
    }
  }

  logStep(`Synced home resource directory: ${targetDir}`);
};

const readMinAppVersionCode = async (): Promise<number | null> => {
  if (!(await exists(androidBuildGradlePath))) {
    return null;
  }

  const content = await readFile(androidBuildGradlePath, "utf8");
  const match = content.match(/versionCode\s*=\s*(\d+)/);
  return match ? Number(match[1]) : null;
};

const buildResourceVersion = (date = new Date()): string => {
  const envVersion = process.env.RESOURCE_VERSION?.trim();
  if (envVersion) return envVersion;

  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  const second = String(date.getSeconds()).padStart(2, "0");
  return `${year}.${month}.${day}.${hour}${minute}${second}`;
};

const currentResourceVersion = buildResourceVersion();

const getResourcePackageUrl = (): string =>
  process.env.RESOURCE_PACKAGE_URL?.trim() || updateZipFileName;

const writeMetaFile = async (): Promise<void> => {
  const metaPath = path.join(androidAssetsDir, "cache-meta.json");
  const meta = {
    resourceVersion: currentResourceVersion,
    exportedAt: new Date().toISOString(),
    sanitized: true,
    files: {
      timetableView: bundledTemplateFiles.timetableView,
      examView: bundledTemplateFiles.examView,
      scoreView: bundledTemplateFiles.scoreView,
      levelExamView: bundledTemplateFiles.levelExamView,
      homeView: bundledTemplateFiles.homeView
    }
  };

  const content = JSON.stringify(meta, null, 2);
  await writeFile(metaPath, content, "utf8");
  await writeTextPackageAsset("cache-meta.json", content);
  logStep(`Wrote metadata file: ${metaPath}`);
};

const sha256 = (content: Buffer): string => createHash("sha256").update(content).digest("hex");

const sha256File = async (filePath: string): Promise<string> => {
  const content = await readFile(filePath);
  return sha256(content);
};

const toZipDateTime = (date: Date): { date: number; time: number } => {
  const year = Math.max(1980, date.getFullYear());
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { date: dosDate, time: dosTime };
};

const crcTable = (() => {
  const table: number[] = [];
  for (let index = 0; index < 256; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  return table;
})();

const crc32 = (content: Buffer): number => {
  let crc = 0xffffffff;
  for (const byte of content) {
    crc = crcTable[(crc ^ byte) & 0xff]! ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
};

const uint16 = (value: number): Buffer => {
  const buffer = Buffer.alloc(2);
  buffer.writeUInt16LE(value);
  return buffer;
};

const uint32 = (value: number): Buffer => {
  const buffer = Buffer.alloc(4);
  buffer.writeUInt32LE(value >>> 0);
  return buffer;
};

const collectFiles = async (dirPath: string, baseDir = dirPath): Promise<string[]> => {
  const entries = await readdir(dirPath, { withFileTypes: true });
  const files: string[] = [];

  for (const entry of entries) {
    const absolutePath = path.join(dirPath, entry.name);
    if (entry.isDirectory()) {
      files.push(...await collectFiles(absolutePath, baseDir));
    } else if (entry.isFile()) {
      files.push(path.relative(baseDir, absolutePath).replace(/\\/g, "/"));
    }
  }

  return files.sort((left, right) => left.localeCompare(right));
};

const writeStoredZip = async (sourceDir: string, targetPath: string): Promise<void> => {
  const relativePaths = await collectFiles(sourceDir);
  const localParts: Buffer[] = [];
  const centralParts: Buffer[] = [];
  let offset = 0;
  const now = new Date();
  const { date, time } = toZipDateTime(now);

  for (const relativePath of relativePaths) {
    const filePath = path.join(sourceDir, ...relativePath.split("/"));
    const content = await readFile(filePath);
    const name = Buffer.from(relativePath, "utf8");
    const checksum = crc32(content);
    const size = content.length;
    const localHeader = Buffer.concat([
      uint32(0x04034b50),
      uint16(20),
      uint16(0x0800),
      uint16(0),
      uint16(time),
      uint16(date),
      uint32(checksum),
      uint32(size),
      uint32(size),
      uint16(name.length),
      uint16(0),
      name
    ]);
    const centralHeader = Buffer.concat([
      uint32(0x02014b50),
      uint16(20),
      uint16(20),
      uint16(0x0800),
      uint16(0),
      uint16(time),
      uint16(date),
      uint32(checksum),
      uint32(size),
      uint32(size),
      uint16(name.length),
      uint16(0),
      uint16(0),
      uint16(0),
      uint16(0),
      uint32(0),
      uint32(offset),
      name
    ]);

    localParts.push(localHeader, content);
    centralParts.push(centralHeader);
    offset += localHeader.length + content.length;
  }

  const centralDirectory = Buffer.concat(centralParts);
  const endOfCentralDirectory = Buffer.concat([
    uint32(0x06054b50),
    uint16(0),
    uint16(0),
    uint16(relativePaths.length),
    uint16(relativePaths.length),
    uint32(centralDirectory.length),
    uint32(offset),
    uint16(0)
  ]);
  const zip = Buffer.concat([...localParts, centralDirectory, endOfCentralDirectory]);

  await writeFile(targetPath, zip);
  logStep(`Wrote resource update package: ${targetPath} (${zip.length} bytes)`);
};

const writeUpdateManifest = async (): Promise<void> => {
  const packageHash = await sha256File(updateZipPath);
  const packageStat = await stat(updateZipPath);
  const minAppVersionCode = await readMinAppVersionCode();
  const packageFiles = await collectFiles(updatePackageRootDir);
  const files = await Promise.all(
    packageFiles.map(async (relativePath) => {
      const filePath = path.join(updatePackageRootDir, ...relativePath.split("/"));
      const fileStat = await stat(filePath);
      return {
        path: relativePath,
        sizeBytes: fileStat.size,
        sha256: await sha256File(filePath)
      };
    })
  );
  const manifest = {
    schemaVersion: 1,
    resourceVersion: currentResourceVersion,
    minAppVersionCode,
    package: {
      fileName: updateZipFileName,
      url: getResourcePackageUrl(),
      sizeBytes: packageStat.size,
      sha256: packageHash
    },
    files
  };

  await writeFile(updateManifestPath, JSON.stringify(manifest, null, 2), "utf8");
  logStep(`Wrote update manifest: ${updateManifestPath}`);
};

const run = async (): Promise<void> => {
  logDivider("ANDROID EXPORT");
  await ensureDir(androidAssetsDir);
  await rm(updatePackageRootDir, { recursive: true, force: true });
  await ensureDir(updatePackageRootDir);
  await ensureDir(updateExportDir);
  const bundledHomeImages = await withStep("读取首页图片清单", () => readBundledHomeImages());

  await writeTextRuntimeAsset(bundledTemplateFiles.timetableView, renderTimetablePage([]));
  await writeTextRuntimeAsset(bundledTemplateFiles.examView, renderExamPage([]));
  await writeTextRuntimeAsset(bundledTemplateFiles.scoreView, renderScorePage([]));
  await writeTextRuntimeAsset(bundledTemplateFiles.levelExamView, renderLevelExamPage([]));
  await writeTextRuntimeAsset(bundledTemplateFiles.homeView, renderHomePage([], bundledHomeImages));

  for (const fileName of personalDataFiles) {
    await removeIfExists(path.join(androidAssetsDir, fileName));
  }

  await withStep("同步 Android 首页图片", () => copyHomeGallery());
  await withStep("写入 Android 资源版本信息", () => writeMetaFile());
  await withStep("打包 Android 资源", () => writeStoredZip(updatePackageRootDir, updateZipPath));
  await withStep("写入 Android 更新清单", () => writeUpdateManifest());
  await rm(updatePackageRootDir, { recursive: true, force: true });

  logStep(`Done. Android assets are sanitized and ready in ${androidAssetsDir}`);
  logStep(`Done. Resource update artifacts are ready in ${updateExportDir}`);
  logDivider("END");
};

run().catch((error: unknown) => {
  const message = error instanceof Error ? error.stack || error.message : String(error);
  console.error(message);
  process.exitCode = 1;
});
