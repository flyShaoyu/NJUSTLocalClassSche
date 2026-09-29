import path from "node:path";
import dotenv from "dotenv";
import { AppConfig } from "./types.js";
import { DEFAULT_LOGIN_URL, TEACHING_ORIGIN, resolveEndpoint } from "./endpoints.js";

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
export const levelExamHtmlPath = path.resolve("artifacts", "level-exam-list.html");
export const levelExamJsonPath = path.resolve("artifacts", "level-exam-list.json");
export const levelExamViewPath = path.resolve("artifacts", "level-exam-view.html");
export const homeViewPath = path.resolve("artifacts", "home-view.html");
export const homeImageArtifactsDir = path.resolve("artifacts", "resources");
export const homeImageSourceDir = path.resolve("resources");

export const loadConfig = (): AppConfig => ({
  baseUrl: resolveEndpoint(process.env.BASE_URL, TEACHING_ORIGIN),
  loginUrl: resolveEndpoint(process.env.LOGIN_URL, DEFAULT_LOGIN_URL),
  timetableUrl:
    resolveEndpoint(process.env.TIMETABLE_URL, `${TEACHING_ORIGIN}/njlgdx/xskb/xskb_list.do`),
  examQueryUrl:
    resolveEndpoint(process.env.EXAM_QUERY_URL, `${TEACHING_ORIGIN}/njlgdx/xsks/xsksap_query`),
  examListUrl:
    resolveEndpoint(process.env.EXAM_LIST_URL, `${TEACHING_ORIGIN}/njlgdx/xsks/xsksap_list`),
  scoreUrl:
    resolveEndpoint(process.env.SCORE_URL, `${TEACHING_ORIGIN}/njlgdx/kscj/cjcx_list`),
  levelExamUrl:
    resolveEndpoint(process.env.LEVEL_EXAM_URL, `${TEACHING_ORIGIN}/njlgdx/kscj/djkscj_list`),
  username: getOptionalEnv("USERNAME"),
  password: getOptionalEnv("PASSWORD"),
  semester: getOptionalEnv("SEMESTER"),
  headless: parseBoolean(process.env.HEADLESS, false),
  loginSuccessSelector: getOptionalEnv("LOGIN_SUCCESS_SELECTOR"),
  manualLoginTimeoutMs: parseNumber(process.env.MANUAL_LOGIN_TIMEOUT_MS, 300000)
});
