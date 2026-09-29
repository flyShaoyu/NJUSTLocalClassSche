import * as cheerio from "cheerio";
import { logStep } from "./logger.js";
import { LevelExamRecord } from "./types.js";

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

export const parseLevelExamHtml = (html: string): LevelExamRecord[] => {
  logStep("Parsing level exam HTML.");

  const $ = cheerio.load(html);
  if (!$("#dataList").length) {
    throw new Error("等级考试解析错误：缺少 #dataList，网页可能跳回登录页或网站结构已改变。");
  }
  const records: LevelExamRecord[] = [];

  $("#dataList tr").each((_, row) => {
    const cells = $(row).children("td").toArray();
    if (cells.length < 9) {
      return;
    }

    const indexText = cleanInlineText($(cells[0]).text());
    const examName = cleanInlineText($(cells[1]).text());
    const writtenScore = cleanInlineText($(cells[2]).text());
    const computerScore = cleanInlineText($(cells[3]).text());
    const totalScore = cleanInlineText($(cells[4]).text());
    const writtenLevel = cleanInlineText($(cells[5]).text());
    const computerLevel = cleanInlineText($(cells[6]).text());
    const totalLevel = cleanInlineText($(cells[7]).text());
    const examDate = cleanInlineText($(cells[8]).text());

    if (!examName && !totalScore && !totalLevel && !examDate) {
      return;
    }

    records.push({
      index: Number(indexText) || records.length + 1,
      examName,
      writtenScore,
      computerScore,
      totalScore,
      writtenLevel,
      computerLevel,
      totalLevel,
      examDate,
      rawText: [
        indexText,
        examName,
        writtenScore,
        computerScore,
        totalScore,
        writtenLevel,
        computerLevel,
        totalLevel,
        examDate
      ]
        .filter(Boolean)
        .join("\n")
    });
  });

  logStep(`Parsed ${records.length} level exam entries.`);
  return records;
};
