import path from "node:path";
import dotenv from "dotenv";
import { AppConfig } from "./types.js";

dotenv.config();

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

export const loadConfig = (): AppConfig => ({
  baseUrl: process.env.BASE_URL?.trim() || "http://202.119.81.113:8080",
  loginUrl: process.env.LOGIN_URL?.trim() || "http://202.119.81.113:8080",
  timetableUrl:
    process.env.TIMETABLE_URL?.trim() ||
    "http://202.119.81.112:9080/njlgdx/xskb/xskb_list.do",
  username: getOptionalEnv("USERNAME"),
  password: getOptionalEnv("PASSWORD"),
  headless: parseBoolean(process.env.HEADLESS, false),
  loginSuccessSelector: getOptionalEnv("LOGIN_SUCCESS_SELECTOR"),
  manualLoginTimeoutMs: parseNumber(process.env.MANUAL_LOGIN_TIMEOUT_MS, 300000)
});
