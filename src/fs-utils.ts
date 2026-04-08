import fs from "node:fs/promises";
import path from "node:path";
import { logStep } from "./logger.js";

export const ensureArtifactsDirectory = async (): Promise<void> => {
  const artifactsDir = path.resolve("artifacts");
  logStep(`Ensuring artifacts directory exists: ${artifactsDir}`);
  await fs.mkdir(artifactsDir, { recursive: true });
};

export const fileExists = async (filePath: string): Promise<boolean> => {
  try {
    await fs.access(filePath);
    return true;
  } catch {
    return false;
  }
};

export const writeTextFile = async (filePath: string, content: string): Promise<void> => {
  logStep(`Writing file: ${filePath}`);
  await fs.writeFile(filePath, content, "utf8");
};
