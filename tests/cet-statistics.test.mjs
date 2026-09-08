import assert from "node:assert/strict";
import test from "node:test";
import vm from "node:vm";
import { renderScorePage } from "../dist/score-ui.js";
import { renderLevelExamPage } from "../dist/level-exam-ui.js";

const evaluatePage = (html) => {
  const context = vm.createContext({
    HTMLElement: class {},
    document: { getElementById: () => null, addEventListener: () => {} }
  });
  vm.runInContext(html.match(/<script>([\s\S]*?)<\/script>/)[1], context);
  return (expression) => vm.runInContext(expression, context);
};

const exam = (score, examName = "CET6") => ({ examName, totalScore: score });

test("only CET scores strictly above 425 are highlighted", () => {
  const evaluate = evaluatePage(renderLevelExamPage([]));
  for (const [record, expected] of [
    [exam("424"), false],
    [exam("425"), false],
    [exam("426"), true],
    [exam("496", "CET4"), true],
    [exam("710", "Placement"), false],
    [exam("496", "\u5168\u56fd\u5927\u5b66\u82f1\u8bed\u56db\u516d\u7ea7\uff08CET4\uff09"), true],
    [exam("496", "\u8ba1\u7b97\u673a\u56db\u7ea7"), false],
    [exam(""), false],
    [{ ...exam("--"), totalLevel: "\u5408\u683c" }, false]
  ]) {
    assert.equal(evaluate(`isPassed(${JSON.stringify(record)})`), expected);
  }
});

test("best CET attempt adds exactly eight credits and uses the existing GPA scale", () => {
  const evaluate = evaluatePage(renderScorePage([], [
    exam("426"), exam("496", "CET4"), exam("329"), exam("496", "CET4"),
    exam("710", "Placement"), exam("710", "\u8ba1\u7b97\u673a\u56db\u7ea7")
  ]));
  assert.equal(evaluate("cetCourse.credits"), 8);
  assert.equal(evaluate("cetCourse.numericScore"), 496 / 710 * 100);
  assert.equal(evaluate("cetCourse.gradePoint"), 2);
  assert.equal(evaluate("summaryRows()[2].items.length"), 1);
  const example = evaluatePage(renderScorePage([], [exam("426")]));
  assert.equal(example("cetCourse.numericScore"), 60);
  assert.equal(example("cetCourse.gradePoint"), 1);
});

test("missing or invalid CET results do not add phantom credits", () => {
  for (const exams of [[], [exam("710", "Placement")], [exam(""), exam("--"), exam("NaN"), exam("711"), exam("-1")]]) {
    const evaluate = evaluatePage(renderScorePage([], exams));
    assert.equal(evaluate("cetCourse"), null);
    assert.equal(evaluate("computeMetrics(summaryRows()[2].items).totalCredits"), 0);
  }
  const evaluate = evaluatePage(renderScorePage([], [exam("425")]));
  assert.equal(evaluate("cetCourse.credits"), 8);
  assert.equal(evaluate("cetCourse.gradePoint"), 0);
});

test("selection updates combined weighted metrics without changing other summaries", () => {
  const evaluate = evaluatePage(renderScorePage([{
    courseCode: "A", courseName: "Course A", credits: "2", score: "90",
    courseAttribute: "\u5fc5\u4fee", semester: "2025-2026-1"
  }, {
    courseCode: "B", courseName: "Course B", credits: "2", score: "80",
    courseAttribute: "\u9009\u4fee", semester: "2025-2026-1"
  }], [exam("426")]));
  assert.equal(evaluate("computeMetrics(summaryRows()[0].items).totalCredits"), 4);
  assert.equal(evaluate("computeMetrics(summaryRows()[1].items).totalCredits"), 2);
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).weightedAverage"), 66);
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).gpa"), 1.6);
  evaluate("toggleRecord('score-1')");
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).totalCredits"), 12);
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).weightedAverage"), 820 / 12);
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).gpa"), 22 / 12);
  evaluate("toggleSemester('2025-2026-1')");
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).totalCredits"), 8);
  assert.equal(evaluate("computeMetrics(summaryRows()[2].items).weightedAverage"), 60);
});
