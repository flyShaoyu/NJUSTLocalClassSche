import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const run=promisify(execFile),root=process.cwd();
async function findJar(dir){for(const e of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isFile()&&e.name.endsWith('.jar'))return p;if(e.isDirectory()){const found=await findJar(p);if(found)return found;}}}
test('自动考试同步：复用会话、查询当前学期、失败不作为空表、变化去重',{timeout:30000},async t=>{
  const classes=path.join(root,'android/app/build/tmp/kotlin-classes/debug');
  try{await fs.access(path.join(classes,'com/classsche/mobile/ExamQueryClient.class'));await run('javac',['-version']);}catch(e){if(e.code!=='ENOENT')throw e;t.skip('先构建 Android 并安装 JDK');return;}
  const parent=path.join(root,'artifacts');await fs.mkdir(parent,{recursive:true});const temp=await fs.mkdtemp(path.join(parent,'exam-sync-test-'));
  try{
    const cache=path.join(process.env.GRADLE_USER_HOME||path.join(os.homedir(),'.gradle'),'caches/modules-2/files-2.1');
    const jars=await Promise.all(['org.jetbrains.kotlin/kotlin-stdlib/1.9.24','org.jsoup/jsoup/1.18.1'].map(p=>findJar(path.join(cache,p))));
    jars.forEach(jar=>assert.ok(jar));const cp=[temp,classes,...jars].join(path.delimiter);
    await run('javac',['-encoding','UTF-8','-cp',cp,'-d',temp,path.join(root,'tests/android/ExamSyncCheck.java')]);
    const {stdout}=await run('java',['-cp',cp,'ExamSyncCheck']);assert.match(stdout,/PASS exam fetch/);process.stdout.write(stdout);
  }finally{assert.equal(path.dirname(temp),parent);await fs.rm(temp,{recursive:true,force:true});}
});
