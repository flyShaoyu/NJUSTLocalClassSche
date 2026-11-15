import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
const run=promisify(execFile),root=process.cwd();
async function findJar(dir){for(const e of await fs.readdir(dir,{withFileTypes:true})){const p=path.join(dir,e.name);if(e.isFile()&&e.name.endsWith('.jar'))return p;if(e.isDirectory()){const found=await findJar(p);if(found)return found;}}}
test('邮件更新间隔：移除选项向较长间隔迁移，保留关闭和现有有效设置',{timeout:30000},async t=>{
  const classes=path.join(root,'android/app/build/tmp/kotlin-classes/debug');
  try{await fs.access(path.join(classes,'com/classsche/mobile/SchoolMailIntervalPolicy.class'));await run('javac',['-version']);}catch(e){if(e.code!=='ENOENT')throw e;t.skip('先构建 Android 并安装 JDK');return;}
  const parent=path.join(root,'artifacts');await fs.mkdir(parent,{recursive:true});const temp=await fs.mkdtemp(path.join(parent,'mail-preferences-test-'));
  try{
    const cache=path.join(process.env.GRADLE_USER_HOME||path.join(os.homedir(),'.gradle'),'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/1.9.24');
    const jar=await findJar(cache);assert.ok(jar);const cp=[temp,classes,jar].join(path.delimiter);
    await run('javac',['-encoding','UTF-8','-cp',cp,'-d',temp,path.join(root,'tests/android/SchoolMailIntervalCheck.java')]);
    const {stdout}=await run('java',['-cp',cp,'SchoolMailIntervalCheck']);assert.match(stdout,/PASS mail intervals/);process.stdout.write(stdout);
  }finally{assert.equal(path.dirname(temp),parent);await fs.rm(temp,{recursive:true,force:true});}
});
