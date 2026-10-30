import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import ts from 'typescript';
import {chromium} from 'playwright';
import {isPublicChallengeHtml,parseGeneralEducationSource,readGeneralEducationSource,writeGeneralEducationCatalog} from '../dist/general-education-source.js';
const url='https://jwc.njust.edu.cn/b1/7f/c1189a307583/page.htm';
const challenge='<html><head><script r="m" src="/challenge.js"></script></head><body></body></html>';
const valid='<html><title>通识教育选修课分类汇总（2022版）</title><script r="m" src="/normal.js"></script><div class="wp_articlecontent"><table><tr><td>课程号</td><td>课程名</td><td>分类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>自然科技类</td></tr><tr><td>00W00184</td><td>药用植物学</td><td>人文素养类</td></tr></table></div></html>';

test('防护脚本单独返回时识别为挑战，正常正文含脚本仍可解析',()=>{
  assert.equal(isPublicChallengeHtml(challenge),true);
  assert.equal(isPublicChallengeHtml(valid),false);
  assert.throws(()=>parseGeneralEducationSource(challenge),/防护校验未完成/);
  assert.throws(()=>parseGeneralEducationSource('<html><title>网站故障</title></html>'),/标题不符/);
  const parsed=parseGeneralEducationSource(valid);assert.equal(parsed.courses.length,2);assert.equal(parsed.courses[0].courseCode,'00W00184');assert.notEqual(parsed.courses[0].category,parsed.courses[1].category);
});
test('防护页、损坏行不覆盖已有目录，合法来源保留冲突并原子替换',async()=>{
  const directory=await fs.mkdtemp(path.resolve('artifacts/source-test-'));
  const output=path.join(directory,'catalog.ts');
  try {
    await fs.writeFile(output,'previous-valid-catalog');
    await assert.rejects(writeGeneralEducationCatalog(challenge,output),/防护校验/);
    await assert.rejects(writeGeneralEducationCatalog(valid.replace('<td>00W00184</td>','<td>184</td>'),output),/第 2 行/);
    assert.equal(await fs.readFile(output,'utf8'),'previous-valid-catalog');
    await writeGeneralEducationCatalog(valid,output);
    const content=await fs.readFile(output,'utf8');assert.match(content,/GENERAL_EDUCATION_CATALOG/);assert.equal((content.match(/"courseCode":"00W00184"/g)||[]).length,2);
    const built=ts.transpileModule(content,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS},reportDiagnostics:true});
    assert.equal((built.diagnostics||[]).filter(x=>x.category===ts.DiagnosticCategory.Error).length,0);
    const runtime={exports:{}};vm.runInNewContext(built.outputText,runtime);assert.equal(runtime.exports.GENERAL_EDUCATION_CATALOG.courses.length,2);
    assert.deepEqual(await fs.readdir(directory),['catalog.ts']);
  }finally{assert.equal(path.dirname(directory),path.resolve('artifacts'));await fs.rm(directory,{recursive:true,force:true});}
});
test('正常浏览器完成验证后只读 DOM，不做第二次 fetch；失败给出防护状态', {timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage();let requests=0;
    await page.route('**/*',route=>{if(route.request().url()===url){requests++;return route.fulfill({status:200,body:valid,contentType:'text/html; charset=utf-8'});}return route.fulfill({body:'',contentType:'application/javascript'});});
    const result=await readGeneralEducationSource(page,1000);assert.equal(result.source.courses.length,2);assert.equal(requests,1);assert.deepEqual(result.statuses,[200]);
    await page.unroute('**/*');await page.route('**/*',route=>route.fulfill({status:202,body:challenge,contentType:'text/html'}));
    await assert.rejects(readGeneralEducationSource(page,400),/网站防护校验未通过：HTTP 202/);
  }finally{await browser.close();}
});
