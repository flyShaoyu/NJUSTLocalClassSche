import { GENERAL_EDUCATION_CATALOG } from "./general-education-catalog.js";
import { ENROLLMENT_CATALOG_LOGIC } from "./enrollment-catalog-ui.js";

/** Kept self-contained so desktop pages and Android-injected scores use one rule. */
export const GENERAL_EDUCATION_LOGIC = String.raw`
    const generalNormalize = value => String(value ?? '').normalize('NFKC').replace(/\s+/g, '').trim();
    const generalCode = value => generalNormalize(value).toUpperCase();
    const generalPassed = value => {
      const text = generalNormalize(value);
      if (!text) return false;
      if (/^\d+(?:\.\d+)?$/.test(text)) return Number(text) >= 60 && Number(text) <= 100;
      return ['优+','优','优-','优秀','良+','良','良-','良好','中+','中','中-','中等','及格','合格','通过','免修'].includes(text);
    };
    const computeGeneralEducation = (scores, catalog, overrides = {}) => {
      const eligible = scores.filter(record => {
        const nature = [record.courseNature,record.courseAttribute].map(generalNormalize).join('|');
        return /计划外|辅修/.test(nature) || (!/必修/.test(nature) && /通识(?:教育)?选修|公共选修|公选课/.test(nature));
      });
      const codesByName = new Map();
      eligible.forEach(record => {
        const name = generalNormalize(record.courseName), code = generalCode(record.courseCode);
        if (name && code) { if (!codesByName.has(name)) codesByName.set(name, new Set()); codesByName.get(name).add(code); }
      });
      const best = new Map();
      eligible.forEach((record,index) => {
        const name = generalNormalize(record.courseName), code = generalCode(record.courseCode);
        const knownCodes = codesByName.get(name);
        const key = code ? 'code:' + code : name ? (knownCodes?.size === 1 ? 'code:' + [...knownCodes][0] : 'name:' + name) : 'row:' + index;
        const numericCredits = String(record.credits ?? '').trim();
        const credits = /^\d+(?:\.\d+)?$/.test(numericCredits) && Number.isFinite(Number(numericCredits)) ? Number(numericCredits) : null;
        const item = {...record,key,credits,passed:generalPassed(record.score)};
        const previous = best.get(key);
        if (!previous || (item.passed && !previous.passed) || (item.passed === previous.passed && String(item.semester || '') >= String(previous.semester || ''))) best.set(key,item);
      });
      const groups = new Map([...new Set([...(catalog.categories || []),'辅修'])].map(category => [category, {category,credits:0,items:[]}]));
      const items = [...best.values()].map(item => {
        const code = generalCode(item.courseCode), name = generalNormalize(item.courseName);
        let matches = code ? catalog.courses.filter(course => generalCode(course.courseCode) === code) : [];
        if (!matches.length && name) matches = catalog.courses.filter(course => generalNormalize(course.courseName) === name);
        const categories = [...new Set(matches.map(course => course.category))];
        const manual = Object.prototype.hasOwnProperty.call(overrides,item.key);
        const minor = /辅修/.test([item.courseNature,item.courseAttribute].join('|'));
        const category = manual ? String(overrides[item.key] || '').trim() : !minor && categories.length === 1 ? categories[0] : '';
        const classificationSource = manual ? '手动分类' : minor ? '辅修课程，请手动分类' : category ? '校方分类表' : categories.length > 1 ? '校方分类冲突：' + categories.join(' / ') : /计划外/.test([item.courseNature,item.courseAttribute].join('|')) ? '计划外课程，请确认选修分类或辅修' : '分类表未收录，请手动确认';
        return {...item,category,classificationSource,earned:item.passed && item.credits !== null && item.credits > 0};
      });
      let totalCredits=0,matchedCredits=0,unmatchedCredits=0;
      const unclassified=[],unearned=[];
      items.forEach(item => {
        if (!item.earned) {unearned.push(item);return;}
        totalCredits += item.credits;
        if (!item.category) {unmatchedCredits += item.credits;unclassified.push(item);return;}
        matchedCredits += item.credits;
        if (!groups.has(item.category)) groups.set(item.category,{category:item.category,credits:0,items:[]});
        const group=groups.get(item.category);group.credits += item.credits;group.items.push(item);
      });
      return {totalCredits,matchedCredits,unmatchedCredits,groups:[...groups.values()],unclassified,unearned,items};
    };
`;

export { GENERAL_EDUCATION_STYLE, GENERAL_EDUCATION_HTML } from "./general-education-pages.js";
import { GENERAL_EDUCATION_PAGES_SCRIPT } from "./general-education-pages.js";
export const buildGeneralEducationScript = (): string => `
${GENERAL_EDUCATION_LOGIC}
${ENROLLMENT_CATALOG_LOGIC}
let generalCatalog = ${JSON.stringify(GENERAL_EDUCATION_CATALOG).replace(/</g, "\\u003c")};
${GENERAL_EDUCATION_PAGES_SCRIPT}
`;
