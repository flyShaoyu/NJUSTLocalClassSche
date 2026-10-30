import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { TimetableCourse } from "./types.js";

export interface LocalCourseInput {
  semester: string; courseName: string; weekday: number; startPeriod: number; endPeriod: number;
  weeks: number[]; classroom?: string; teacher?: string;
}
export interface LocalCourse extends LocalCourseInput { id: string; source: "manual" }
const weekdays = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"];

export function createLocalCourse(input: LocalCourseInput, id: string = randomUUID()): LocalCourse {
  if (!/^\d{4}-\d{4}-[123]$/.test(input.semester)) throw new Error("添加课程参数错误：学期格式应为 2026-2027-1。");
  const courseName = input.courseName.trim();
  if (!courseName || courseName.length > 120) throw new Error("添加课程参数错误：课程名称应为 1—120 个字符。");
  if (!Number.isInteger(input.weekday) || input.weekday < 1 || input.weekday > 7) throw new Error("添加课程参数错误：星期应为 1—7。");
  if (!Number.isInteger(input.startPeriod) || !Number.isInteger(input.endPeriod) || input.startPeriod < 1 || input.endPeriod > 14 || input.endPeriod < input.startPeriod) throw new Error("添加课程参数错误：节次范围应在 1—14 内，且结束不能早于开始。");
  if (!Array.isArray(input.weeks) || !input.weeks.length || input.weeks.some(w => !Number.isInteger(w) || w < 1 || w > 30)) throw new Error("添加课程参数错误：周次应为 1—30 的非空整数数组。");
  if (!id || typeof id !== "string") throw new Error("本地课程解析错误：课程 ID 为空。");
  return {
    semester: input.semester, courseName, weekday: input.weekday, startPeriod: input.startPeriod, endPeriod: input.endPeriod,
    weeks: [...new Set(input.weeks)].sort((a, b) => a - b), classroom: input.classroom?.trim() || "", teacher: input.teacher?.trim() || "", id, source: "manual"
  };
}

export async function readLocalCourses(file: string): Promise<LocalCourse[]> {
  let text: string;
  try { text = await fs.readFile(file, "utf8"); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return []; throw error; }
  try {
    const data = JSON.parse(text);
    if (data.schemaVersion !== 1 || !Array.isArray(data.courses)) throw new Error("数据版本或课程数组不正确");
    const courses: LocalCourse[] = data.courses.map((course: LocalCourse) => {
      if (!course.id || course.source !== "manual") throw new Error("缺少本地课程 ID 或来源");
      return createLocalCourse(course, course.id);
    });
    if (new Set(courses.map(c => c.id)).size !== courses.length) throw new Error("课程 ID 重复");
    return courses;
  } catch (error) { throw new Error(`本地课程解析错误：${file}，${error instanceof Error ? error.message : String(error)}；原文件未覆盖。`); }
}

export async function saveLocalCourses(file: string, courses: LocalCourse[]): Promise<void> {
  const normalized = courses.map(course => {
    if (course.source !== "manual" || !course.id) throw new Error("本地课程保存错误：必须有手动课程来源和 ID，不允许写入教务课程。");
    return createLocalCourse(course, course.id);
  });
  if (new Set(normalized.map(c => c.id)).size !== normalized.length) throw new Error("本地课程保存错误：课程 ID 重复。");
  await fs.mkdir(path.dirname(file), { recursive: true });
  const temp = `${file}.${randomUUID()}.tmp`;
  try {
    await fs.writeFile(temp, JSON.stringify({ schemaVersion: 1, courses: normalized }, null, 2) + "\n", { flag: "wx" });
    await fs.rename(temp, file);
  } finally { await fs.rm(temp, { force: true }); }
}

export function mergeLocalCourses(online: TimetableCourse[], local: LocalCourse[], semester: string): TimetableCourse[] {
  return [...online, ...local.filter(c => c.semester === semester).map(c => ({
    courseName: c.courseName, weekday: weekdays[c.weekday - 1]!, periods: `${c.startPeriod}-${c.endPeriod}节`,
    weeks: c.weeks.join(",") + "周", classroom: c.classroom || "", teacher: c.teacher || "", courseType: "手动添加",
    rawText: `${c.courseName}\n${weekdays[c.weekday - 1]}(${c.startPeriod}-${c.endPeriod}节)\n${c.classroom || ""}`
  }))];
}
