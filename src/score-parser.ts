import * as cheerio from "cheerio";
import { logStep } from "./logger.js";
import { ScoreRecord } from "./types.js";

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

export const parseScoreHtml = (html: string): ScoreRecord[] => {
  logStep("Parsing score HTML.");

  const $ = cheerio.load(html);
  const scores: ScoreRecord[] = [];

  $("#dataList tr")
    .toArray()
    .slice(1)
    .forEach((row) => {
      const cells = $(row).children("td").toArray();
      if (cells.length < 10) {
        return;
      }

      const [
        indexCell,
        semesterCell,
        courseCodeCell,
        courseNameCell,
        scoreCell,
        scoreIdentifierCell,
        creditsCell,
        totalHoursCell,
        assessmentMethodCell,
        courseAttributeCell,
        courseNatureCell
      ] = cells;

      const indexText = cleanInlineText($(indexCell).text());
      const semester = cleanInlineText($(semesterCell).text());
      const courseCode = cleanInlineText($(courseCodeCell).text());
      const courseName = cleanInlineText($(courseNameCell).text());
      const score = cleanInlineText($(scoreCell).text());
      const scoreIdentifier = cleanInlineText($(scoreIdentifierCell).text());
      const credits = cleanInlineText($(creditsCell).text());
      const totalHours = cleanInlineText($(totalHoursCell).text());
      const assessmentMethod = cleanInlineText($(assessmentMethodCell).text());
      const courseAttribute = cleanInlineText($(courseAttributeCell).text());
      const courseNature = cleanInlineText($(courseNatureCell).text());
      const scoreStyle = ($(scoreCell).attr("style") ?? "").toLowerCase();

      if (!semester && !courseCode && !courseName && !score) {
        return;
      }

      scores.push({
        index: Number(indexText) || scores.length + 1,
        semester,
        courseCode,
        courseName,
        score,
        scoreIdentifier,
        credits,
        totalHours,
        assessmentMethod,
        courseAttribute,
        courseNature,
        isHighlighted: /color\s*:\s*red/.test(scoreStyle),
        rawText: [
          indexText,
          semester,
          courseCode,
          courseName,
          score,
          scoreIdentifier,
          credits,
          totalHours,
          assessmentMethod,
          courseAttribute,
          courseNature
        ]
          .filter(Boolean)
          .join("\n")
      });
    });

  logStep(`Parsed ${scores.length} score entries.`);
  return scores;
};
