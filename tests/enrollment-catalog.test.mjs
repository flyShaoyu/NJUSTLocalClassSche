import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {chromium} from 'playwright';
import {GENERAL_EDUCATION_CATALOG} from '../dist/general-education-catalog.js';
import {ENROLLMENT_CATALOG_LOGIC} from '../dist/enrollment-catalog-ui.js';
import {renderCampusToolsPage} from '../dist/campus-tools-ui.js';

const logic=vm.runInNewContext(ENROLLMENT_CATALOG_LOGIC+';({buildEnrollmentCatalog,filterEnrollmentCatalog})');
const clean=value=>JSON.parse(JSON.stringify(value));

test('选课分类目录复用 742 行原表，按课程号去重、保留多分类并支持搜索筛选',()=>{
  const items=logic.buildEnrollmentCatalog(GENERAL_EDUCATION_CATALOG);
  assert.equal(items.length,741);
  const conflict=clean(logic.filterEnrollmentCatalog(items,'00w00184',''));
  assert.equal(conflict.length,1);assert.equal(conflict[0].courseName,'药用植物学');
  assert.equal(conflict[0].categories.length,2);assert.equal(conflict[0].conflict,true);
  assert.equal(logic.filterEnrollmentCatalog(items,'药用','人文素养类').length,1);
  assert.equal(logic.filterEnrollmentCatalog(items,'药用','自然科技类').length,1);
  assert.equal(logic.filterEnrollmentCatalog(items,'','__conflict__').length,1);
  assert.equal(logic.filterEnrollmentCatalog(items,'００００００１１','')[0].courseName,'坦克学');
  assert.equal(logic.filterEnrollmentCatalog(items,'坦克 00000011','自然科技类').length,1);
  assert.equal(logic.filterEnrollmentCatalog(items,'坦克','艺术审美类').length,0);
  assert.equal(logic.filterEnrollmentCatalog(items,'','自然技术类').length,3);
  assert.equal(logic.filterEnrollmentCatalog(items,'','经济社会类（四史）').length,10);
  assert.throws(()=>logic.buildEnrollmentCatalog({courses:[{courseCode:'bad'}]}),/解析错误/);
});

test('课程分类读取成绩页同一份本机更正，保留校方原始分类，不修改原目录',()=>{
  const original=JSON.stringify(GENERAL_EDUCATION_CATALOG);
  const items=logic.buildEnrollmentCatalog(GENERAL_EDUCATION_CATALOG,{'code:00W00184':'我的分类'});
  const result=clean(logic.filterEnrollmentCatalog(items,'药用','我的分类'));
  assert.equal(result.length,1);assert.equal(result[0].conflict,false);assert.equal(result[0].corrected,true);
  assert.deepEqual(result[0].categories,['我的分类']);assert.equal(result[0].officialCategories.length,2);
  assert.equal(JSON.stringify(GENERAL_EDUCATION_CATALOG),original);
});

test('选课入口可打开分类页：搜索、筛选、分页、更正共享、文本安全且不发选课请求',{timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:393,height:800}});page.setDefaultTimeout(5000);
    const errors=[],schoolRequests=[];page.on('pageerror',error=>errors.push(error.message));
    page.on('request',request=>{if(!request.url().startsWith('https://classsche.local/'))schoolRequests.push(request.url());});
    await page.addInitScript(()=>{
      window.calls=[];window.opened=[];
      window.CampusBridge={request(id,action){window.calls.push(action);setTimeout(()=>window.CampusCallbacks.receive(id,{ok:true,data:{page:new URL(location.href).searchParams.get('tool')||'enrollment',enrollment:{enabled:false,browseEnabled:true}}}),5);},openTool(key){window.opened.push(key);}};
    });
    await page.route('https://classsche.local/**',route=>route.fulfill({body:renderCampusToolsPage(),contentType:'text/html; charset=utf-8'}));
    const open=async tool=>{await page.goto('https://classsche.local/campus-tools/?tool='+tool);await page.evaluate(()=>window.CampusUI.start());};
    await open('toolbox');
    assert.equal(await page.getByRole('button',{name:/选课 \/ 抢课/}).isEnabled(),true);
    await page.getByRole('button',{name:/选课 \/ 抢课/}).click();
    assert.deepEqual(await page.evaluate(()=>window.opened),['enrollment']);
    await open('enrollment');
    await page.locator('.enrollment-course').first().waitFor();
    assert.match(await page.locator('#enrollmentSummary').innerText(),/741/);
    assert.equal(await page.locator('.enrollment-course').count(),30);
    assert.equal(await page.locator('#enrollmentPrevious').isEnabled(),false);
    assert.equal(await page.locator('#enrollmentPageLabel').innerText(),'1 / 25');
    const first=await page.locator('.enrollment-course h3').first().innerText();
    await page.locator('#enrollmentNext').click();assert.equal(await page.locator('#enrollmentPageLabel').innerText(),'2 / 25');
    assert.notEqual(await page.locator('.enrollment-course h3').first().innerText(),first);
    await page.locator('#enrollmentKeyword').fill('００００００１１');
    assert.equal(await page.locator('.enrollment-course h3').innerText(),'坦克学');
    await page.locator('#enrollmentCategory').selectOption('艺术审美类');assert.equal(await page.locator('.enrollment-course').count(),0);
    await page.locator('#clearEnrollment').click();assert.equal(await page.locator('.enrollment-course').count(),30);
    await page.locator('#enrollmentCategory').selectOption('经济社会类（四史）');assert.equal(await page.locator('.enrollment-course').count(),10);
    await page.locator('#enrollmentCategory').selectOption('__conflict__');assert.match(await page.locator('#enrollmentResults').innerText(),/药用植物学[\s\S]*人文素养类[\s\S]*自然科技类|药用植物学[\s\S]*自然科技类[\s\S]*人文素养类/);
    assert.equal(await page.locator('.enrollment-course button:enabled').count(),0);
    await page.evaluate(()=>localStorage.setItem('classsche.general-education.categories.v1',JSON.stringify({'code:00000011':'本机类别'})));
    await open('enrollment');await page.locator('#enrollmentCategory').selectOption('本机类别');
    assert.match(await page.locator('#enrollmentResults').innerText(),/坦克学[\s\S]*本机分类更正/);
    await page.evaluate(()=>{generalCatalog={categories:['艺术审美类'],courses:[{courseCode:'00000001',courseName:'<img src=x onerror=alert(1)>',category:'艺术审美类'}]};showEnrollment();});
    assert.equal(await page.locator('img').count(),0);assert.match(await page.locator('.enrollment-course h3').innerText(),/<img/);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.deepEqual(await page.evaluate(()=>window.calls),['bootstrap']);
    assert.deepEqual(schoolRequests,[]);assert.deepEqual(errors,[]);
  }finally{await browser.close();}
});
