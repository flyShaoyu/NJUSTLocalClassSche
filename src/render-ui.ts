import fs from "node:fs/promises";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
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
const execFileAsync = promisify(execFile);

const powershellQuote = (value: string): string => `'${value.replace(/'/g, "''")}'`;

const resizeImageVariant = async (
  sourcePath: string,
  targetPath: string,
  maxWidth: number,
  maxHeight: number
): Promise<void> => {
  const command = `
Add-Type -AssemblyName System.Drawing
$source = ${powershellQuote(sourcePath)}
$target = ${powershellQuote(targetPath)}
$maxWidth = ${maxWidth}
$maxHeight = ${maxHeight}
$image = [System.Drawing.Image]::FromFile($source)
try {
  $ratio = [Math]::Min($maxWidth / [double]$image.Width, $maxHeight / [double]$image.Height)
  if ($ratio -ge 1) {
    Copy-Item -LiteralPath $source -Destination $target -Force
    return
  }

  $newWidth = [Math]::Max(1, [int][Math]::Round($image.Width * $ratio))
  $newHeight = [Math]::Max(1, [int][Math]::Round($image.Height * $ratio))
  $bitmap = New-Object System.Drawing.Bitmap($newWidth, $newHeight)
  try {
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
      $graphics.Clear([System.Drawing.Color]::Black)
      $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
      $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
      $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
      $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
      $graphics.DrawImage($image, 0, 0, $newWidth, $newHeight)
    } finally {
      $graphics.Dispose()
    }

    $codec = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' } | Select-Object -First 1
    if ($null -eq $codec) {
      $bitmap.Save($target, [System.Drawing.Imaging.ImageFormat]::Jpeg)
    } else {
      $encoder = [System.Drawing.Imaging.Encoder]::Quality
      $params = New-Object System.Drawing.Imaging.EncoderParameters(1)
      $params.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter($encoder, 90L)
      try {
        $bitmap.Save($target, $codec, $params)
      } finally {
        $params.Dispose()
      }
    }
  } finally {
    $bitmap.Dispose()
  }
} finally {
  $image.Dispose()
}
`;

  await execFileAsync("powershell.exe", ["-NoProfile", "-Command", command], { windowsHide: true });
};

const collectHomeImages = async (): Promise<Array<{ fileName: string; src: string; detailSrc: string; fullSrc: string }>> => {
  await fs.mkdir(homeImageSourceDir, { recursive: true });
  await fs.rm(homeImageArtifactsDir, { recursive: true, force: true });
  await fs.mkdir(homeImageArtifactsDir, { recursive: true });

  const entries = await fs.readdir(homeImageSourceDir, { withFileTypes: true });
  const imageEntries = entries.filter((entry) => entry.isFile() && imageExtensions.has(path.extname(entry.name).toLowerCase()));

  return Promise.all(
    imageEntries.map(async (entry, index) => {
      const sourcePath = path.join(homeImageSourceDir, entry.name);
      const extension = path.extname(entry.name).toLowerCase();
      const safeBaseName = `image-${String(index + 1).padStart(3, "0")}`;
      const fullFileName = `${safeBaseName}${extension}`;
      const thumbFileName = `${safeBaseName}-thumb.jpg`;
      const detailFileName = `${safeBaseName}-detail.jpg`;
      const fullTargetPath = path.join(homeImageArtifactsDir, fullFileName);
      const thumbTargetPath = path.join(homeImageArtifactsDir, thumbFileName);
      const detailTargetPath = path.join(homeImageArtifactsDir, detailFileName);

      await fs.copyFile(sourcePath, fullTargetPath);

      let thumbSrc = `./resources/${fullFileName}`;
      let detailSrc = `./resources/${fullFileName}`;

      try {
        await resizeImageVariant(sourcePath, thumbTargetPath, 960, 960);
        await resizeImageVariant(sourcePath, detailTargetPath, 2200, 2200);
        thumbSrc = `./resources/${thumbFileName}`;
        detailSrc = `./resources/${detailFileName}`;
      } catch {
        await fs.rm(thumbTargetPath, { force: true });
        await fs.rm(detailTargetPath, { force: true });
      }

      return {
        fileName: entry.name,
        src: thumbSrc,
        detailSrc,
        fullSrc: `./resources/${fullFileName}`
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
