import * as cheerio from "cheerio";
import { ExamArrangement, TimetableCourse } from "./types.js";
import { logStep } from "./logger.js";

const normalizeText = (value: string): string =>
  value
    .replace(/\u00a0/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/\r/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

const cleanInlineText = (value: string): string =>
  normalizeText(value).replace(/\s*\n\s*/g, " ");

const normalizeLookupKey = (value: string): string =>
  cleanInlineText(value).replace(/\s+/g, "").toLowerCase();

const addTeacher = (index: Map<string, Set<string>>, key: string, teacher: string | undefined): void => {
  const normalizedKey = normalizeLookupKey(key);
  const normalizedTeacher = cleanInlineText(teacher ?? "");

  if (!normalizedKey || !normalizedTeacher) {
    return;
  }

  const teachers = index.get(normalizedKey) ?? new Set<string>();
  teachers.add(normalizedTeacher);
  index.set(normalizedKey, teachers);
};

const buildTeacherIndex = (courses: TimetableCourse[]): Map<string, Set<string>> => {
  const index = new Map<string, Set<string>>();

  for (const course of courses) {
    addTeacher(index, course.courseCode ?? "", course.teacher);
    addTeacher(index, course.courseName, course.teacher);
  }

  return index;
};

const findTeacher = (
  teacherIndex: Map<string, Set<string>>,
  courseCode: string,
  courseName: string
): string | undefined => {
  const teachers =
    teacherIndex.get(normalizeLookupKey(courseCode)) ??
    teacherIndex.get(normalizeLookupKey(courseName));

  if (!teachers || teachers.size === 0) {
    return undefined;
  }

  return [...teachers].join("、");
};

export const parseExamArrangementHtml = (
  html: string,
  courses: TimetableCourse[] = []
): ExamArrangement[] => {
  logStep("Parsing exam arrangement HTML.");

  const $ = cheerio.load(html);
  const teacherIndex = buildTeacherIndex(courses);
  const exams: ExamArrangement[] = [];

  $("#dataList tr")
    .toArray()
    .slice(1)
    .forEach((row) => {
      const cells = $(row).children("td").toArray();
      if (cells.length < 7) {
        return;
      }

      const indexText = cleanInlineText($(cells[0]).text());
      const examSession = cleanInlineText($(cells[1]).text());
      const courseCode = cleanInlineText($(cells[2]).text());
      const courseName = cleanInlineText($(cells[3]).text());
      const examTime = cleanInlineText($(cells[4]).text());
      const examRoom = cleanInlineText($(cells[5]).text());
      const seatNumber = cleanInlineText($(cells[6]).text());
      const teacher = findTeacher(teacherIndex, courseCode, courseName);

      if (!courseCode && !courseName && !examTime) {
        return;
      }

      exams.push({
        index: Number(indexText) || exams.length + 1,
        examSession,
        courseCode,
        courseName,
        examTime,
        examRoom,
        seatNumber,
        teacher,
        rawText: [
          indexText,
          examSession,
          courseCode,
          courseName,
          teacher,
          examTime,
          examRoom,
          seatNumber
        ]
          .filter(Boolean)
          .join("\n")
      });
    });

  logStep(`Parsed ${exams.length} exam arrangement entries.`);
  return exams;
};
