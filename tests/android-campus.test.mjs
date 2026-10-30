import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const run=promisify(execFile),root=process.cwd();
async function findJar(dir){for(const e of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isFile()&&e.name.endsWith('.jar'))return p;if(e.isDirectory()){const f=await findJar(p);if(f)return f;}}}
test('Android 校园查询解析器与日程时间校验', {timeout:30000}, async t=>{
  const classes=path.join(root,'android/app/build/tmp/kotlin-classes/debug');
  try{await fs.access(path.join(classes,'com/classsche/mobile/CampusDataParser.class'));await run('javac',['-version']);}catch(e){if(e.code!=='ENOENT')throw e;t.skip('先构建 Android 并安装 JDK');return;}
  const artifacts=path.join(root,'artifacts');await fs.mkdir(artifacts,{recursive:true});const temporary=await fs.mkdtemp(path.join(artifacts,'campus-test-'));
  try{
    const deps=[];const cache=path.join(process.env.GRADLE_USER_HOME||path.join(os.homedir(),'.gradle'),'caches/modules-2/files-2.1');
    for(const p of ['org.jetbrains.kotlin/kotlin-stdlib/1.9.24','org.jsoup/jsoup/1.18.1']){const jar=await findJar(path.join(cache,p));assert.ok(jar);const local=path.join(temporary,path.basename(jar));await fs.copyFile(jar,local);deps.push(local);}
    const android=path.join(process.env.LOCALAPPDATA,'Android/Sdk/platforms/android-35/android.jar');
    const cp=[temporary,classes,android,...deps].join(path.delimiter);
    await run('javac',['-encoding','UTF-8','-cp',cp,'-d',temporary,path.join(root,'tests/android/CampusParserCheck.java')]);
    const files=['artifacts/home-probe/public-kc.html','artifacts/home-probe/public-teacher.html'];
    const actual=(await Promise.all(files.map(p=>fs.access(p).then(()=>true,()=>false)))).every(Boolean)?files:[];
    if(actual.length && await fs.access('artifacts/通识教育选修课分类汇总（2022版）.htm').then(()=>true,()=>false))actual.push('artifacts/通识教育选修课分类汇总（2022版）.htm');
    const {stdout}=await run('java',['-cp',cp,'CampusParserCheck',...actual]);process.stdout.write(stdout);assert.match(stdout,/PASS Android room/);
  } finally{assert.equal(path.dirname(temporary),artifacts);await fs.rm(temporary,{recursive:true,force:true});}
});
