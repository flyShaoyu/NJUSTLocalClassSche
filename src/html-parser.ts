import * as cheerio from "cheerio";
import { TimetableCourse } from "./types.js";
import { logStep } from "./logger.js";

interface GridMeeting {
  courseName: string;
  teacher: string;
  classroom: string;
  weeks: string;
  weekday: string;
  rowLabel: string;
  rawText: string;
}

interface DetailMeeting extends TimetableCourse {
  matchKey: string;
}

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

const splitHtmlLines = (html: string): string[] =>
  normalizeText(
    html
      .replace(/<br\s*\/?>/gi, "\n")
      .replace(/<\/(div|p|li|tr|font)>/gi, "\n")
      .replace(/<[^>]+>/g, "")
  )
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);

const splitSegments = (lines: string[]): string[][] => {
  const segments: string[][] = [];
  let current: string[] = [];

  for (const line of lines) {
    if (/^-{5,}$/.test(line)) {
      if (current.length > 0) {
        segments.push(current);
        current = [];
      }
      continue;
    }

    current.push(line);
  }

  if (current.length > 0) {
    segments.push(current);
  }

  return segments;
};

const buildKey = (courseName: string, teacher: string, classroom: string, weekday: string): string =>
  [courseName, teacher, classroom, weekday].map((part) => cleanInlineText(part)).join("||");

const fallbackKey = (courseName: string, teacher: string, weekday: string): string =>
  [courseName, teacher, weekday].map((part) => cleanInlineText(part)).join("||");

const parseWeekdayHeader = (value: string): string => cleanInlineText(value);

const parseGridMeetingSegment = (segment: string[], weekday: string, rowLabel: string): GridMeeting | null => {
  if (segment.length === 0) {
    return null;
  }

  const courseName = segment[0] ?? "";
  const weekIndex = segment.findIndex((line) => /周/.test(line));
  const weeks = weekIndex >= 0 ? (segment[weekIndex] ?? "") : "";
  const classroom =
    weekIndex >= 0 && weekIndex < segment.length - 1 ? (segment[segment.length - 1] ?? "") : "";

  let teacher = "";
  if (weekIndex > 1) {
    teacher = segment[weekIndex - 1] ?? "";
  }

  if (weekIndex > 2 && classroom && classroom === teacher) {
    teacher = segment[weekIndex - 2] ?? teacher;
  }

  return {
    courseName: cleanInlineText(courseName),
    teacher: cleanInlineText(teacher),
    classroom: cleanInlineText(classroom),
    weeks: cleanInlineText(weeks),
    weekday,
    rowLabel: cleanInlineText(rowLabel),
    rawText: segment.join("\n")
  };
};

const parseGridMeetings = ($: cheerio.CheerioAPI): GridMeeting[] => {
  const table = $("#kbtable").first();
  const rows = table.find("tr").toArray();
  if (rows.length === 0) {
    return [];
  }

  const weekdayHeaders = $(rows[0])
    .children("th")
    .toArray()
    .slice(1)
    .map((cell) => parseWeekdayHeader($(cell).text()));

  const meetings: GridMeeting[] = [];

  for (const row of rows.slice(1)) {
    const cells = $(row).children("th,td").toArray();
    if (cells.length <= 1) {
      continue;
    }

    const rowLabel = cleanInlineText($(cells[0]).text());

    cells.slice(1).forEach((cell, columnIndex) => {
      const weekday = weekdayHeaders[columnIndex] ?? `星期${columnIndex + 1}`;
      $(cell)
        .find("div.kbcontent")
        .each((_, detailDiv) => {
          const lines = splitHtmlLines($(detailDiv).html() ?? "");
          const segments = splitSegments(lines);

          for (const segment of segments) {
            const meeting = parseGridMeetingSegment(segment, weekday, rowLabel);
            if (meeting && meeting.courseName) {
              meetings.push(meeting);
            }
          }
        });
    });
  }

  return meetings;
};

const splitTimeEntries = (html: string): Array<{ weekday: string; periods: string }> => {
  return splitHtmlLines(html)
    .map((line) => {
      const match = line.match(/^(星期[一二三四五六日天])\((.+?)\)$/);
      if (!match) {
        return null;
      }

      return {
        weekday: match[1],
        periods: match[2]
      };
    })
    .filter((item): item is { weekday: string; periods: string } => Boolean(item));
};

const splitClassrooms = (value: string, expectedCount: number): string[] => {
  const classrooms = cleanInlineText(value)
    .split(/\s*,\s*/)
    .map((part) => part.trim())
    .filter(Boolean);

  if (classrooms.length === expectedCount) {
    return classrooms;
  }

  if (classrooms.length === 1 && expectedCount > 1) {
    return Array.from({ length: expectedCount }, () => classrooms[0] ?? "");
  }

  return classrooms;
};

const buildGridMeetingIndex = (gridMeetings: GridMeeting[]): Map<string, GridMeeting[]> => {
  const index = new Map<string, GridMeeting[]>();

  for (const meeting of gridMeetings) {
    const exact = buildKey(meeting.courseName, meeting.teacher, meeting.classroom, meeting.weekday);
    const loose = fallbackKey(meeting.courseName, meeting.teacher, meeting.weekday);

    index.set(exact, [...(index.get(exact) ?? []), meeting]);
    index.set(loose, [...(index.get(loose) ?? []), meeting]);
  }

  return index;
};

const takeWeeks = (
  index: Map<string, GridMeeting[]>,
  courseName: string,
  teacher: string,
  classroom: string,
  weekday: string
): { weeks: string; rawText: string } => {
  const exact = buildKey(courseName, teacher, classroom, weekday);
  const loose = fallbackKey(courseName, teacher, weekday);

  const exactMatches = index.get(exact);
  if (exactMatches && exactMatches.length > 0) {
    const meeting = exactMatches.shift() as GridMeeting;
    return { weeks: meeting.weeks, rawText: meeting.rawText };
  }

  const looseMatches = index.get(loose);
  if (looseMatches && looseMatches.length > 0) {
    const meeting = looseMatches.shift() as GridMeeting;
    return { weeks: meeting.weeks, rawText: meeting.rawText };
  }

  return { weeks: "", rawText: "" };
};

const parseDataListMeetings = ($: cheerio.CheerioAPI, gridMeetings: GridMeeting[]): DetailMeeting[] => {
  const index = buildGridMeetingIndex(gridMeetings);
  const meetings: DetailMeeting[] = [];

  $("#dataList tr")
    .toArray()
    .slice(1)
    .forEach((row) => {
      const cells = $(row).children("td").toArray();
      if (cells.length < 10) {
        return;
      }

      const courseCode = cleanInlineText($(cells[1]).text());
      const courseSequence = cleanInlineText($(cells[2]).text());
      const courseName = cleanInlineText($(cells[3]).text());
      const teacher = cleanInlineText($(cells[4]).text());
      const timeEntries = splitTimeEntries($(cells[5]).html() ?? "");
      const classrooms = splitClassrooms($(cells[7]).text(), timeEntries.length);
      const courseType = cleanInlineText($(cells[8]).text());

      timeEntries.forEach((entry, indexInRow) => {
        const classroom = classrooms[indexInRow] ?? classrooms[0] ?? "";
        const matched = takeWeeks(index, courseName, teacher, classroom, entry.weekday);

        meetings.push({
          courseName,
          weekday: entry.weekday,
          periods: entry.periods,
          classroom,
          weeks: matched.weeks,
          teacher,
          courseCode,
          courseSequence,
          courseType,
          rawText: matched.rawText || `${courseName}\n${teacher}\n${entry.weekday}(${entry.periods})\n${classroom}`,
          matchKey: buildKey(courseName, teacher, classroom, entry.weekday)
        });
      });
    });

  return meetings;
};

export const parseTimetableHtml = (html: string): TimetableCourse[] => {
  logStep("Parsing timetable HTML.");

  const $ = cheerio.load(html);
  const gridMeetings = parseGridMeetings($);
  logStep(`Parsed ${gridMeetings.length} grid timetable entries with week information.`);

  const detailMeetings = parseDataListMeetings($, gridMeetings).map(({ matchKey, ...course }) => course);
  logStep(`Parsed ${detailMeetings.length} timetable entries from the detail table.`);

  return detailMeetings;
};
