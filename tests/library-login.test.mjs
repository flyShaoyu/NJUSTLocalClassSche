import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {chromium} from 'playwright';
const base='http://202.119.83.14:8080/uopac/';
async function submitScript(){
 const source=await fs.readFile('android/app/src/main/java/com/classsche/mobile/LibraryLoginActivity.kt','utf8');
 const raw=source.match(/val script="""(\(\(\)=>\{[\s\S]*?\}\)\(\))"""\.trimIndent\(\)/)[1];
 const values={
  '${JSONObject.quote(CampusDataClient.LIBRARY)}':JSON.stringify(base),
  '${JSONObject.quote(CampusDataClient.LIBRARY+"reader/redr_verify.php")}':JSON.stringify(base+'reader/redr_verify.php'),
  '${JSONObject.quote(type)}':JSON.stringify('bar_no'),
  '${JSONObject.quote(username)}':JSON.stringify('fake-user-<>&'),
  '${JSONObject.quote(password)}':JSON.stringify('fake-password-<>&'),
  '${JSONObject.quote(captcha)}':JSON.stringify('TEST')
 };
 return Object.entries(values).reduce((s,[key,value])=>s.split(key).join(value),raw);
}
const form=`<form method="POST" action="redr_verify.php" onsubmit='event.preventDefault();window.submissions++;this.elements.passwd.value="encoded:"+this.elements.passwd.value'><input name=number><input name=passwd type=password><input name=captcha><input name=select type=radio value=cert_no checked><input name=select type=radio value=bar_no><input type=submit></form><script>window.submissions=0</script>`;
test('本地登录通过官网表单提交一次，保留编码/CSRF流程并正确选择条码号',async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage();await page.route('**/*',r=>r.fulfill({body:form,contentType:'text/html'}));await page.goto(base+'reader/login.php');
  assert.equal(await page.evaluate(await submitScript()),true);
  assert.equal(await page.evaluate(()=>window.submissions),1);
  assert.equal(await page.locator('[name=number]').inputValue(),'fake-user-<>&');
  assert.equal(await page.locator('[name=passwd]').inputValue(),'encoded:fake-password-<>&');
  assert.equal(await page.locator('[name=captcha]').inputValue(),'TEST');
  assert.equal(await page.locator('[name=select]:checked').inputValue(),'bar_no');
  await page.goto(base+'reader/login.php');await page.evaluate(()=>document.querySelector('form').requestSubmit=undefined);
  assert.equal(await page.evaluate(await submitScript()),true);assert.equal(await page.evaluate(()=>window.submissions),1);
 }finally{await browser.close();}
});
test('登录目标异常时不填凭据、不提交，也不把登录页文本作为执行指令',async()=>{
 const browser=await chromium.launch({headless:true});try{
  const page=await browser.newPage();await page.route('**/*',r=>r.fulfill({body:form,contentType:'text/html'}));await page.goto(base+'reader/login.php');
  await page.evaluate(()=>document.querySelector('form').action='https://example.org/collect');
  assert.equal(await page.evaluate(await submitScript()),false);assert.equal(await page.evaluate(()=>window.submissions),0);assert.equal(await page.locator('[name=passwd]').inputValue(),'');
 }finally{await browser.close();}
});
