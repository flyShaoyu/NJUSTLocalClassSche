import test from 'node:test';
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {renderScorePage} from '../dist/score-ui.js';

const storageKey='classsche.general-education.categories.v1';
const scores=[
  {courseCode:'00000001',courseName:'课程甲',credits:'2',score:'80',courseNature:'计划外'},
  {courseCode:'00000002',courseName:'课程乙',credits:'1',score:'90',courseNature:'计划外'}
];
const catalog={imported:true,categories:['人文素养类','艺术审美类'],courses:[{courseCode:'00000001',courseName:'课程甲',category:'人文素养类'},{courseCode:'00000002',courseName:'课程乙',category:'人文素养类'}]};
const card=page=>page.locator('[data-course-key="code:00000001"]');
const zone=(page,name)=>page.locator('[data-category-drop]').filter({has:page.locator('h2 span',{hasText:new RegExp('^'+name+'$')})});
async function openEditor(page){
  await page.route('https://classsche.local/score-view/',route=>route.fulfill({body:renderScorePage(scores),contentType:'text/html'}));
  await page.goto('https://classsche.local/score-view/');
  await page.evaluate(catalog=>window.GeneralEducationPage.updateCatalog(catalog),catalog);
  await page.locator('#openGeneralEducation').click();await page.locator('#generalToolbarAction').click();
}
async function point(locator){await locator.scrollIntoViewIfNeeded();const box=await locator.boundingBox();return {x:box.x+box.width/2,y:box.y+Math.min(box.height/2,20)};}
async function mouseDrop(page,target){const p=await point(target);await page.mouse.move(p.x,p.y,{steps:8});await page.mouse.up();}

test('三条杠立即拖动跨分类，取消不改草稿，保存后重开和刷新仍保留记录',{timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try {
    const page=await browser.newPage({viewport:{width:430,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
    await openEditor(page);
    let p=await point(card(page).locator('.general-drag-handle'));await page.mouse.move(p.x,p.y);await page.mouse.down();
    assert.equal(await page.locator('.general-drag-ghost').count(),1);
    const ghost=await page.locator('.general-drag-ghost').boundingBox();assert.ok(Math.abs(ghost.y+ghost.height/2-p.y)<2,'浮层中心跟随触点，不再偏下');
    await mouseDrop(page,zone(page,'艺术审美类').locator('h2'));
    assert.equal(await zone(page,'艺术审美类').locator('[data-course-key="code:00000001"]').count(),1);
    assert.equal(await page.evaluate(key=>localStorage.getItem(key),storageKey),null);
    p=await point(card(page).locator('.general-drag-handle'));await page.mouse.move(p.x,p.y);await page.mouse.down();
    await page.keyboard.press('Escape');await page.mouse.up();
    assert.equal(await page.locator('.general-drag-ghost').count(),0);assert.equal(await page.title(),'学分统计');
    assert.equal(await zone(page,'艺术审美类').locator('[data-course-key="code:00000001"]').count(),1);
    await page.evaluate(()=>window.GeneralEducationPage.toolbarAction());
    assert.equal(await page.evaluate(key=>JSON.parse(localStorage.getItem(key))['code:00000001'],storageKey),'艺术审美类');
    assert.match(await page.locator('#generalCategoryList').innerText(),/艺术审美类.*2 学分/s);
    await page.reload();await page.locator('#openGeneralEducation').click();
    await page.evaluate(catalog=>window.GeneralEducationPage.updateCatalog(catalog),catalog);
    await page.evaluate(()=>window.GeneralEducationPage.edit());
    assert.equal(await zone(page,'艺术审美类').locator('[data-course-key="code:00000001"]').count(),1);
    // Manual pending must override the official match and survive saving, too.
    p=await point(card(page).locator('.general-drag-handle'));await page.mouse.move(p.x,p.y);await page.mouse.down();await mouseDrop(page,zone(page,'待分类').locator('h2'));
    assert.equal(await card(page).locator('select').inputValue(),'__pending__');
    await page.evaluate(()=>window.GeneralEducationPage.toolbarAction());await page.reload();await page.locator('#openGeneralEducation').click();
    assert.match(await page.locator('#generalUnclassified').innerText(),/课程甲/);
    assert.deepEqual(errors,[]);
  } finally {await browser.close();}
});

test('手机触摸：普通滑动不触发，长按课程或直接拖手柄，边缘滚动与取消',{timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try {
    const context=await browser.newContext({viewport:{width:393,height:800},isMobile:true,hasTouch:true});
    const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));await openEditor(page);
    const cdp=await context.newCDPSession(page);
    const touch=(type,p)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:p?[{...p,id:1,radiusX:1,radiusY:1}]:[]});
    // A normal swipe may scroll, but must never start a drag.
    let p=await point(card(page).locator('h3'));await touch('touchStart',p);await touch('touchMove',{x:p.x,y:p.y-45});await touch('touchEnd');
    assert.equal(await page.locator('.general-drag-ghost').count(),0);
    p=await point(card(page).locator('h3'));await touch('touchStart',p);
    await page.waitForSelector('.general-drag-ghost',{timeout:2000});
    const before=await page.evaluate(()=>scrollY);await touch('touchMove',{x:190,y:790});
    await page.waitForFunction(y=>scrollY>y+30,before);
    let target=await point(zone(page,'艺术审美类').locator('h2'));await touch('touchMove',target);await touch('touchEnd');
    assert.equal(await zone(page,'艺术审美类').locator('[data-course-key="code:00000001"]').count(),1);
    // Handles work without the long-press delay.
    p=await point(card(page).locator('.general-drag-handle'));await touch('touchStart',p);
    assert.equal(await page.locator('.general-drag-ghost').count(),1);
    target=await point(zone(page,'辅修').locator('h2'));await touch('touchMove',target);
    const ghostTouch=await page.locator('.general-drag-ghost').boundingBox();assert.ok(Math.abs(ghostTouch.y+ghostTouch.height/2-target.y)<2,'触摸移动也使用同一坐标');await touch('touchEnd');
    assert.equal(await zone(page,'辅修').locator('[data-course-key="code:00000001"]').count(),1);
    p=await point(card(page).locator('.general-drag-handle'));await touch('touchStart',p);await touch('touchCancel');
    assert.equal(await page.locator('.general-drag-ghost').count(),0);
    assert.equal(await zone(page,'辅修').locator('[data-course-key="code:00000001"]').count(),1);
    await page.evaluate(()=>window.GeneralEducationPage.toolbarAction());
    assert.equal(await page.evaluate(key=>JSON.parse(localStorage.getItem(key))['code:00000001'],storageKey),'辅修');
    assert.deepEqual(errors,[]);
  } finally {await browser.close();}
});


test('分类在统计页内编辑，删除只移至待分类，空分类才有下拉，搜索统一边距与总学分', {timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:393,height:800}});await openEditor(page);
  assert.equal(await page.locator('#generalStatsView').isVisible(),true);
  assert.equal(await page.locator('#generalClassificationView').count(),0);
  assert.equal(await card(page).locator('select').count(),0);
  assert.match(await page.locator('#generalTotals').innerText(),/总学分\n3 学分/);
  const rect=await page.locator('#generalSearch').boundingBox();assert.equal(rect.x,12);assert.equal(rect.width,369);
  assert.equal(await page.locator('#generalSearch').evaluate(el=>getComputedStyle(el).borderRadius),'0px');
  await card(page).locator('[data-general-remove]').click();
  assert.equal(await card(page).getAttribute('data-course-category'),'');
  assert.equal(await card(page).locator('select').inputValue(),'__pending__');
  assert.equal(await page.locator('[data-course-key="code:00000002"] select').count(),0);
  assert.equal(await page.locator('.general-course-note').count(),0);
  await page.evaluate(()=>window.GeneralEducationPage.toolbarAction());await page.reload();await page.locator('#openGeneralEducation').click();
  assert.match(await page.locator('#generalUnclassified').innerText(),/课程甲/);
  assert.match(await page.locator('#generalTotals').innerText(),/总学分\n3 学分/);
 }finally{await browser.close();}
});


test('恢复默认只改草稿，返回可放弃，保存后移除手动分类并在重载后使用默认',{timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({viewport:{width:393,height:800}});await openEditor(page);
  const custom={'code:00000001':'辅修','code:00000002':''};await page.evaluate(({key,value})=>localStorage.setItem(key,JSON.stringify(value)),{key:storageKey,value:custom});
  await page.reload();await page.evaluate(catalog=>window.GeneralEducationPage.updateCatalog(catalog),catalog);await page.evaluate(()=>window.GeneralEducationPage.edit());
  const hintBox=await page.locator('#generalEditMessage').boundingBox(),resetBox=await page.locator('#generalResetDefaults').boundingBox();
  assert.ok(resetBox.x>=hintBox.x+hintBox.width,'恢复默认位于提示右侧');assert.ok(resetBox.y<hintBox.y+hintBox.height&&hintBox.y<resetBox.y+resetBox.height,'按钮与提示同行');
  assert.equal(await card(page).getAttribute('data-course-category'),'辅修');await page.locator('#generalResetDefaults').click();
  assert.equal(await card(page).getAttribute('data-course-category'),'人文素养类');assert.deepEqual(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)),storageKey),custom);
  page.once('dialog',dialog=>dialog.accept());await page.evaluate(()=>window.GeneralEducationPage.back());
  assert.match(await page.locator('#generalCategoryList').innerText(),/辅修/);
  await page.evaluate(()=>window.GeneralEducationPage.edit());await page.locator('#generalResetDefaults').click();await page.evaluate(()=>window.GeneralEducationPage.toolbarAction());
  assert.deepEqual(await page.evaluate(key=>JSON.parse(localStorage.getItem(key)),storageKey),{});
  await page.reload();await page.evaluate(catalog=>window.GeneralEducationPage.updateCatalog(catalog),catalog);await page.locator('#openGeneralEducation').click();
  assert.match(await page.locator('#generalCategoryList').innerText(),/人文素养类/);assert.doesNotMatch(await page.locator('#generalCategoryList').innerText(),/辅修/);assert.equal(await page.locator('#generalResetDefaults').isVisible(),false);
 }finally{await browser.close();}
});
