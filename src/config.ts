import path from "node:path";
import dotenv from "dotenv";
import { AppConfig } from "./types.js";

dotenv.config({ override: true });

const parseBoolean = (value: string | undefined, fallback: boolean): boolean => {
  if (value === undefined || value.trim() === "") {
    return fallback;
  }

  return value.toLowerCase() === "true";
};

const parseNumber = (value: string | undefined, fallback: number): number => {
  if (value === undefined || value.trim() === "") {
    return fallback;
  }

  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const getOptionalEnv = (name: string): string | undefined => {
  const value = process.env[name]?.trim();
  return value || undefined;
};

export const storageStatePath = path.resolve("artifacts", "storageState.json");
export const timetableHtmlPath = path.resolve("artifacts", "timetable.html");
export const timetableJsonPath = path.resolve("artifacts", "timetable.json");
export const timetableViewPath = path.resolve("artifacts", "timetable-view.html");
export const examHtmlPath = path.resolve("artifacts", "exam-list.html");
export const examJsonPath = path.resolve("artifacts", "exam-list.json");
export const examViewPath = path.resolve("artifacts", "exam-view.html");
export const scoreHtmlPath = path.resolve("artifacts", "score-list.html");
export const scoreJsonPath = path.resolve("artifacts", "score-list.json");
export const scoreViewPath = path.resolve("artifacts", "score-view.html");
export const homeViewPath = path.resolve("artifacts", "home-view.html");
export const homeImageArtifactsDir = path.resolve("artifacts", "resources");
export const homeImageSourceDir = path.resolve("resources");

export const loadConfig = (): AppConfig => ({
  baseUrl: process.env.BASE_URL?.trim() || "http://202.119.81.113:8080",
  loginUrl: process.env.LOGIN_URL?.trim() || "http://202.119.81.113:8080",
  timetableUrl:
    process.env.TIMETABLE_URL?.trim() ||
    "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do",
  examQueryUrl:
    process.env.EXAM_QUERY_URL?.trim() ||
    "http://202.119.81.112:9080/njlgdx/xsks/xsksap_query",
  examListUrl:
    process.env.EXAM_LIST_URL?.trim() ||
    "http://202.119.81.112:9080/njlgdx/xsks/xsksap_list",
  scoreUrl:
    process.env.SCORE_URL?.trim() ||
    "http://202.119.81.112:9080/njlgdx/kscj/cjcx_list",
  username: getOptionalEnv("USERNAME"),
  password: getOptionalEnv("PASSWORD"),
  semester: process.env.SEMESTER?.trim() || "2025-2026-2",
  headless: parseBoolean(process.env.HEADLESS, false),
  loginSuccessSelector: getOptionalEnv("LOGIN_SUCCESS_SELECTOR"),
  manualLoginTimeoutMs: parseNumber(process.env.MANUAL_LOGIN_TIMEOUT_MS, 300000)
});
