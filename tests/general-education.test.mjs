import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {chromium} from 'playwright';
import {GENERAL_EDUCATION_LOGIC} from '../dist/general-education-ui.js';
import {renderScorePage} from '../dist/score-ui.js';
import {GENERAL_EDUCATION_CATALOG} from '../dist/general-education-catalog.js';
const score=(code,name,credits,grade,extra={})=>({courseCode:code,courseName:name,credits,score:grade,courseNature:'通识教育选修课',courseAttribute:'任选',semester:'2025-2026-1',...extra});
const catalog={categories:['人文类','艺术类'],courses:[{courseCode:'A',courseName:'课程甲',category:'人文类'},{courseName:'课程乙（网络）',category:'艺术类'}]};
function compute(scores,catalogue=catalog,overrides={}){const ctx=vm.createContext({scores,catalogue,overrides});vm.runInContext(GENERAL_EDUCATION_LOGIC,ctx);return JSON.parse(vm.runInContext('JSON.stringify(computeGeneralEducation(scores,catalogue,overrides))',ctx));}

test('学校分类表完整导入 742 行，保留分类冲突与原始标签',()=>{
  const catalog=GENERAL_EDUCATION_CATALOG;
  assert.equal(catalog.imported,true);assert.equal(catalog.courses.length,742);
  assert.equal(new Set(catalog.courses.map(x=>x.courseCode)).size,741);
  const counts=Object.fromEntries(catalog.categories.map(category=>[category,catalog.courses.filter(x=>x.category===category).length]));
  assert.deepEqual(counts,{'人文素养类':130,'经济社会类':100,'经济社会类（四史）':10,'艺术审美类':110,'自然科技类':389,'自然技术类':3});
  const all=compute(catalog.courses.map(x=>score(x.courseCode,x.courseName,'1','80')),catalog);
  assert.equal(all.totalCredits,741);assert.equal(all.matchedCredits,740);assert.equal(all.unmatchedCredits,1);
  assert.equal(all.unclassified[0].courseCode,'00W00184');assert.match(all.unclassified[0].classificationSource,/分类冲突/);
  const samples=compute([score('00000052','艺术与美育','2','80'),score('00w00184','药用植物学','1','通过'),score('00000367','习近平总书记关于教育重要论述研究','1','80')],catalog);
  assert.equal(samples.groups.find(x=>x.category==='艺术审美类').credits,2);
  assert.equal(samples.groups.find(x=>x.category==='经济社会类（四史）').credits,1);
  assert.equal(samples.unclassified.length,1);
});

test('通识选修学分只计通过课程，重修只累计一次，排除专业及必修课程',()=>{
  const result=compute([
    score('A','课程甲','2','不及格'),score('A','课程甲','2','合格',{semester:'2026-2027-1'}),
    score('B','课程乙(网络)','1.5','优秀'),score('C','未知课程','1','75'),
    score('D','缺考课程','2','缺考'),score('E','未知成绩','1','待发布'),
    score('F','专业任选','3','90',{courseNature:'专业教育课'}),
    score('G','通识必修','2','85',{courseNature:'通识教育课',courseAttribute:'必修'})
  ]);
  assert.equal(result.totalCredits,4.5);assert.equal(result.matchedCredits,3.5);assert.equal(result.unmatchedCredits,1);
  assert.equal(result.groups[0].credits,2);assert.equal(result.groups[1].credits,1.5);
  assert.equal(result.unearned.length,2);assert.equal(result.items.length,5);
});
test('精确匹配、同名歧义和无效学分保留待核对，人工更正不改变成绩',()=>{
  const data=[score('A','课程甲','2','80'),score('','课程甲','2','85'),score('B','相似名称','2','60'),score('C','坏学分','--','90')];
  const empty={categories:[],courses:[]};const unknown=compute(data,empty);assert.equal(unknown.totalCredits,4);assert.equal(unknown.unmatchedCredits,4);assert.equal(unknown.items.length,3);
  const corrected=compute(data,empty,{'code:A':'自定类'});assert.equal(corrected.matchedCredits,2);assert.equal(corrected.groups.find(x=>x.category==='自定类').credits,2);assert.equal(data[0].score,'80');
  const conflict=compute([score('X','同名课程','2','90')],{categories:['人文类','艺术类'],courses:[{courseName:'同名课程',category:'人文类'},{courseName:'同名课程',category:'艺术类'}]});assert.equal(conflict.matchedCredits,0);assert.equal(conflict.unclassified.length,1);
});
test('计划外纳入候选，辅修必须手动分类，主修必修不混入统计',()=>{
  const scores=[score('A','课程甲','2','80',{courseNature:'计划外'}),score('M','辅修课','3','90',{courseNature:'辅修',courseAttribute:'必修'}),score('U','未知计划外','1','70',{courseNature:'计划外'}),score('R','主修必修','4','80',{courseNature:'专业课',courseAttribute:'必修'})];
  const auto=compute(scores,{...catalog,courses:[...catalog.courses,{courseCode:'M',courseName:'辅修课',category:'人文类'}]});
  assert.equal(auto.totalCredits,6);assert.equal(auto.matchedCredits,2);assert.equal(auto.unclassified.length,2);assert.equal(auto.groups.find(x=>x.category==='辅修').credits,0);
  const manual=compute(scores,catalog,{'code:M':'辅修','code:U':'艺术类'});
  assert.equal(manual.groups.find(x=>x.category==='辅修').credits,3);assert.equal(manual.groups.find(x=>x.category==='艺术类').credits,1);assert.equal(manual.items.length,3);
});
test('学分页面按类别展示、统一保存分类、搜索分类表并逐级返回', {timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:393,height:800}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
    const scores=[score('A','通识 <img src=x>','2','80'),score('B','未通过','1','59'),score('P','专业课','3','90',{courseNature:'专业教育课',courseAttribute:'必修'})];
    await page.route('https://classsche.local/score-view/',route=>route.fulfill({body:renderScorePage(scores),contentType:'text/html'}));
    await page.goto('https://classsche.local/score-view/');
    await page.locator('#openGeneralEducation').click();
    assert.equal(await page.locator('#scoreOverview').isVisible(),false);
    assert.match(await page.locator('#generalTotals').innerText(),/辅修/);
    assert.match(await page.locator('#generalCreditHint').innerText(),/已获 2 学分/);
    assert.equal(await page.locator('#generalStatsView select').count(),0);
    assert.equal(await page.locator('img').count(),0);
    await page.locator('#generalToolbarAction').click();
    assert.equal(await page.title(),'学分统计');
    await page.locator('[data-general-key="code:A"]').selectOption('辅修');
    assert.equal(await page.evaluate(()=>localStorage.getItem('classsche.general-education.categories.v1')),null);
    await page.locator('#generalToolbarAction').click();
    assert.equal(await page.title(),'学分统计');
    assert.match(await page.locator('#generalCategoryList').innerText(),/辅修.*2 学分/s);
    assert.match(await page.locator('#generalTotals').innerText(),/辅修\n2 学分/);
    await page.locator('#openGeneralCatalog').click();
    assert.equal(await page.title(),'课程分类表');
    await page.locator('#generalCatalogSearch').fill('00000052');
    assert.equal(await page.locator('#generalCatalogRows article').count(),1);
    assert.match(await page.locator('#generalCatalogRows').innerText(),/艺术与美育/);
    await page.locator('#generalCatalogSearch').fill('查无此课程');
    assert.match(await page.locator('#generalCatalogCount').innerText(),/找到 0 门/);
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.back()),true);
    assert.equal(await page.title(),'学分统计');
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.back()),true);
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.back()),false);
    await page.reload();await page.locator('#openGeneralEducation').click();
    assert.match(await page.locator('#generalCategoryList').innerText(),/辅修/);
    await page.locator('#closeGeneralEducation').click();
    assert.equal(await page.locator('#scoreOverview').isVisible(),true);
    await page.locator('#openGeneralEducation').click();
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.updateCatalog({title:'手机分类表',categories:['新分类'],courses:[{courseCode:'A',courseName:'通识 <img src=x>',category:'新分类'}],imported:true,cacheSource:'mobile',capturedOn:'2026-10-29'})),true);
    assert.match(await page.locator('#generalCatalogNotice').innerText(),/本机分类表.*2026-10-29/);
    // A mobile refresh must preserve the user's locally saved category override.
    await page.evaluate(()=>window.GeneralEducationPage.edit());
    assert.equal(await page.locator('[data-course-key="code:A"]').getAttribute('data-course-category'),'辅修');
    assert.equal(await page.locator('[data-course-key="code:A"] select').count(),0);
    await page.locator('[data-general-remove="code:A"]').click();
    await page.locator('[data-general-key="code:A"]').selectOption('新分类');
    page.once('dialog',dialog=>dialog.dismiss());
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.back()),true);
    assert.equal(await page.title(),'学分统计');
    page.once('dialog',dialog=>dialog.accept());
    await page.evaluate(()=>window.GeneralEducationPage.back());
    assert.match(await page.locator('#generalCategoryList').innerText(),/辅修/);
    assert.equal(await page.evaluate(()=>window.GeneralEducationPage.updateCatalog({courses:[],categories:[]})),false);
    assert.match(await page.locator('#generalCatalogNotice').innerText(),/本机分类表/);
    assert.deepEqual(errors,[]);
  }finally{await browser.close();}
});

test('集中分类写入失败保留旧值，目录刷新不丢失未保存草稿', {timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try {
    const page=await browser.newPage();
    await page.addInitScript(()=>{
      localStorage.setItem('classsche.general-education.categories.v1',JSON.stringify({'code:A':'辅修'}));
      const original=Storage.prototype.setItem;
      Storage.prototype.setItem=function(key,value){if(key==='classsche.general-education.categories.v1')throw new DOMException('Storage full','QuotaExceededError');return original.call(this,key,value);};
    });
    await page.route('https://classsche.local/score-view/',route=>route.fulfill({body:renderScorePage([score('A','测试课程','2','80',{courseNature:'计划外'})]),contentType:'text/html'}));
    await page.goto('https://classsche.local/score-view/');
    await page.locator('#openGeneralEducation').click();await page.locator('#generalToolbarAction').click();
    await page.locator('[data-general-remove="code:A"]').click();
    await page.locator('[data-general-key="code:A"]').selectOption('艺术审美类');
    await page.evaluate(()=>window.GeneralEducationPage.updateCatalog({imported:true,categories:['新类别'],courses:[{courseCode:'00000001',courseName:'其他课',category:'新类别'}]}));
    assert.equal(await page.locator('[data-course-key="code:A"]').getAttribute('data-course-category'),'艺术审美类');
    await page.locator('#generalToolbarAction').click();
    assert.equal(await page.title(),'学分统计');
    assert.match(await page.locator('#generalEditMessage').innerText(),/分类保存失败/);
    assert.equal(await page.evaluate(()=>JSON.parse(localStorage.getItem('classsche.general-education.categories.v1'))['code:A']),'辅修');
    page.once('dialog',dialog=>dialog.accept());await page.evaluate(()=>window.GeneralEducationPage.back());
    assert.match(await page.locator('#generalCategoryList').innerText(),/辅修.*2 学分/s);
  } finally {await browser.close();}
});
