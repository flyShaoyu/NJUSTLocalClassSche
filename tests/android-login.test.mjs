import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const run = promisify(execFile);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

async function findJar(directory) {
  for (const entry of await fs.readdir(directory, { withFileTypes: true })) {
    const candidate = path.join(directory, entry.name);
    if (entry.isFile() && entry.name.endsWith('.jar')) return candidate;
    if (entry.isDirectory()) {
      const found = await findJar(candidate);
      if (found) return found;
    }
  }
}

test('Android session reuse, expired-session fallback and automatic sync timing', { timeout: 30000 }, async (t) => {
  const classes = path.join(root, 'android/app/build/tmp/kotlin-classes/debug');
  try {
    for (const type of ['HeadlessLoginClient', 'LoginCookieStore', 'ScoreSyncTiming', 'ScoreSyncTriggerPolicy']) {
      await fs.access(path.join(classes, `com/classsche/mobile/${type}.class`));
    }
    await run('javac', ['-version']);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    t.skip('Requires a JDK and :app:assembleDebug; desktop-only checks may skip Android fixtures');
    return;
  }
  const artifacts = path.join(root, 'artifacts');
  await fs.mkdir(artifacts, { recursive: true });
  const temporary = await fs.mkdtemp(path.join(artifacts, 'android-login-test-'));
  try {
    const cache = path.join(process.env.GRADLE_USER_HOME ?? path.join(os.homedir(), '.gradle'), 'caches/modules-2/files-2.1');
    const dependencies = [];
    for (const location of ['org.jetbrains.kotlin/kotlin-stdlib/1.9.24', 'org.jsoup/jsoup/1.18.1']) {
      const jar = await findJar(path.join(cache, location));
      assert.ok(jar, `Missing cached dependency ${location}; build :app:assembleDebug first`);
      const local = path.join(temporary, path.basename(jar));
      await fs.copyFile(jar, local);
      dependencies.push(local);
    }
    const classpath = [temporary, classes, ...dependencies].join(path.delimiter);
    await run('javac', ['-encoding', 'UTF-8', '-cp', classpath, '-d', temporary,
      path.join(root, 'tests/android/fixtures/org/json/JSONObject.java'), path.join(root, 'tests/android/SessionReuseCheck.java')]);
    const { stdout } = await run('java', ['-cp', classpath, 'SessionReuseCheck']);
    assert.match(stdout, /PASS simultaneous refresh\/sync/);
    assert.match(stdout, /PASS startup sync once per process/);
    process.stdout.write(stdout);
  } finally {
    assert.equal(path.dirname(temporary), artifacts);
    await fs.rm(temporary, { recursive: true, force: true });
  }
});
