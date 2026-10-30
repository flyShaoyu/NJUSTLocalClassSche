import test from 'node:test';
import assert from 'node:assert/strict';
import {chromium} from 'playwright';
import {renderExamPage} from '../dist/exam-ui.js';
const exam=(name,time)=>({index:1,examSession:'40001',courseCode:'TEST',courseName:name,examTime:time,examRoom:'模拟地点',seatNumber:'18',rawText:''});
test('考试默认只打开最近未结束的一场，结束/未知时间不默认打开，手动展开仍可用',{timeout:30000},async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage({timezoneId:'Asia/Shanghai'});
  await page.clock.install({time:new Date('2026-10-30T10:00:00+08:00')});
  const open=async data=>{await page.goto('about:blank');await page.setContent(renderExamPage(data));};
  await open([exam('较远','2026-11-01 09:00~11:00'),exam('已结束','2026-10-29 09:00~11:00'),exam('最近','2026-10-30 12:00~14:00'),exam('未知','待安排')]);
  assert.equal(await page.locator('.exam-card.active').count(),1);
  assert.equal(await page.locator('.exam-card.active').getAttribute('data-index'),'2');
  await open([exam('将来','2026-10-30 12:00~14:00'),exam('进行中','2026-10-30 09:00~11:00')]);
  assert.equal(await page.locator('.exam-card.active').getAttribute('data-index'),'1');
  await open([exam('刚结束','2026-10-30 08:00~10:00'),exam('过去','2026-10-29 09:00~11:00'),exam('未知','待安排')]);
  assert.equal(await page.locator('.exam-card.active').count(),0);
  await page.locator('.card-head').first().click();assert.equal(await page.locator('.exam-card.active').count(),1);
  await open([]);assert.equal(await page.locator('.exam-card').count(),0);
 }finally{await browser.close();}
});
