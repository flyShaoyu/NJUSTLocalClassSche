import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {chromium} from 'playwright';
import {renderCampusToolsPage} from '../dist/campus-tools-ui.js';
const run=promisify(execFile);
async function jar(dir){for(const e of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isFile()&&e.name.endsWith('.jar'))return p;if(e.isDirectory()){const found=await jar(p);if(found)return found;}}}

test('评教原生引擎离线服务：只读预览、真实状态确认、无重复提交及中断恢复',{timeout:30000},async t=>{
  const root=process.cwd(),classes=path.join(root,'android/app/build/tmp/kotlin-classes/debug');
  try{await fs.access(path.join(classes,'com/classsche/mobile/TeachingEvaluationEngine.class'));await run('javac',['-version']);}catch(error){if(error.code!=='ENOENT')throw error;t.skip('先构建 Android 并安装 JDK');return;}
  const artifacts=path.join(root,'artifacts'),temp=await fs.mkdtemp(path.join(artifacts,'evaluation-test-'));
  try{
    const cache=path.join(process.env.GRADLE_USER_HOME||path.join(os.homedir(),'.gradle'),'caches/modules-2/files-2.1');
    const deps=[];for(const p of ['org.jetbrains.kotlin/kotlin-stdlib/1.9.24','org.jsoup/jsoup/1.18.1']){const found=await jar(path.join(cache,p));assert.ok(found);deps.push(found);}
    const cp=[temp,classes,...deps].join(path.delimiter);
    await run('javac',['-encoding','UTF-8','-cp',cp,'-d',temp,'tests/android/EvaluationCheck.java']);
    const {stdout}=await run('java',['-cp',cp,'EvaluationCheck']);process.stdout.write(stdout);assert.match(stdout,/PASS evaluation: timeout/);
  }finally{assert.equal(path.dirname(temp),artifacts);await fs.rm(temp,{recursive:true,force:true});}
});

test('评教页面：默认评级、逐题预览、配置失效、进度、去重点击、结果待核实与登录提示',{timeout:30000},async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const page=await browser.newPage({viewport:{width:393,height:800}});page.setDefaultTimeout(5000);
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.evaluate(()=>{
      window.calls=[];window.loginError=false;window.cancelCount=0;
      window.CampusBridge={request(id,action,raw){
        const data=JSON.parse(raw);window.calls.push({action,data});let result={};
        if(action==='bootstrap')result={page:'evaluation'};
        if(action==='evaluation.scan'){
          if(window.loginError){setTimeout(()=>window.CampusCallbacks.receive(id,{ok:false,error:'登录状态已失效',code:'LOGIN_REQUIRED'}),20);return;}
          result={id:'plan',courses:[{title:'课程 <img src=x onerror=alert(1)>',choices:['教学内容：很符合（第 1 项）','综合评价：较符合（第 2 项）']}],notices:[]};
        }
        if(action==='evaluation.submit'){
          setTimeout(()=>window.CampusCallbacks.progress(id,{stage:'正在提交评价',done:0,total:1,title:'课程'}),30);
          setTimeout(()=>window.CampusCallbacks.receive(id,{ok:true,data:{items:[{title:'课程',state:'uncertain',message:'网页连接超时，结果待核实；不会自动重试',url:'https://bkjw.njust.edu.cn/njlgdx/xspj/xspj_find.do'}],stopped:true,notices:[]}}),180);
          return;
        }
        setTimeout(()=>window.CampusCallbacks.receive(id,{ok:true,data:result}),20);
      },cancelEvaluation(){window.cancelCount++;},openUrl(url){window.opened=url;},openLogin(){window.loginOpened=true;}};
    });
    await page.setContent(renderCampusToolsPage());await page.evaluate(()=>window.CampusUI.start());
    await page.locator('#scanEvaluation').waitFor();
    assert.equal(await page.locator('#evaluationOption').inputValue(),'0');assert.equal(await page.locator('#evaluationLast').inputValue(),'1');
    await page.locator('#scanEvaluation').dblclick({force:true});
    await page.getByText('教学内容：很符合（第 1 项）',{exact:false}).waitFor();
    assert.equal((await page.evaluate(()=>window.calls.filter(x=>x.action==='evaluation.scan'))).length,1);
    assert.equal(await page.locator('img').count(),0);
    await page.locator('#evaluationOption').selectOption('1');assert.equal(await page.locator('#submitEvaluation').isEnabled(),false);
    await page.locator('#scanEvaluation').click();await page.waitForFunction(()=>!document.getElementById('submitEvaluation').disabled);
    await page.locator('#submitEvaluation').dblclick({force:true});
    await page.locator('#stopEvaluation').click();assert.equal(await page.evaluate(()=>window.cancelCount),1);
    await page.getByText('结果待核实',{exact:true}).waitFor();
    assert.equal((await page.evaluate(()=>window.calls.filter(x=>x.action==='evaluation.submit'))).length,1);
    assert.equal(await page.locator('#submitEvaluation').isEnabled(),false);
    assert.match(await page.locator('#evaluationProgress').innerText(),/已停止/);
    await page.evaluate(()=>window.loginError=true);await page.locator('#scanEvaluation').click();
    await page.locator('[data-login]').click();assert.equal(await page.evaluate(()=>window.loginOpened),true);
    assert.deepEqual(errors,[]);
  }finally{await browser.close();}
});
