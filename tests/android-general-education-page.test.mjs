import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {chromium} from 'playwright';
const url='https://jwc.njust.edu.cn/b1/7f/c1189a307583/page.htm';
test('手机实际 DOM 读取脚本等待正文、保留冲突并拒绝错误页', {timeout:30000},async()=>{
  const kotlin=await fs.readFile('android/app/src/main/java/com/classsche/mobile/GeneralEducationPageScript.kt','utf8');
  const script=kotlin.match(/val READ_CATALOG = """([\s\S]*?)"""\.trimIndent\(\)/)[1];
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage();let html='';let requests=0;
    await page.route('**/*',route=>{requests++;return route.fulfill({body:html,contentType:'text/html; charset=utf-8'});});
    const read=async body=>{html=body;await page.goto(url);return JSON.parse(await page.evaluate(script));};
    const challenge=await read('<script r="m"></script>');assert.equal(challenge.state,'waiting');assert.equal(challenge.challenge,true);
    const table='<div class=wp_articlecontent><table><tr><td>课程号</td><td>课程名</td><td>分类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>自然科技类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>人文素养类</td></tr></table></div>';
    const before=requests;const ready=await read('<title>通识教育选修课分类汇总（2022版）</title>'+table);
    assert.equal(ready.state,'ready');assert.match(ready.html,/人文素养类/);assert.match(ready.html,/自然科技类/);assert.equal((ready.html.match(/00W00184/g)||[]).length,2);assert.equal(requests,before+1);
    assert.equal((await read('<title>网站错误</title>'+table)).state,'error');
    assert.equal((await read('<title>通识教育选修课分类汇总（2022版）</title>'+table.replace('<td>分类</td>','<td>其他列</td>'))).state,'error');
    await page.goto('about:blank');assert.equal(JSON.parse(await page.evaluate(script)).state,'waiting');
    await page.goto('https://jwc.njust.edu.cn/wrong-page.htm');assert.equal(JSON.parse(await page.evaluate(script)).state,'redirect');
  }finally{await browser.close();}
});
