import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { chromium } from 'playwright';

// Execute the exact script shipped by Android, rather than a second implementation.
const kotlin = await readFile(new URL('../android/app/src/main/java/com/classsche/mobile/IdentityQrPageScript.kt', import.meta.url), 'utf8');
const source = kotlin.match(/private val SOURCE = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(source);
let browser;
before(async () => { browser = await chromium.launch({ headless: true }); });
after(async () => { await browser?.close(); });

const fixture = `<!doctype html><html><body>
  <div id="qrLoginDiv" hidden><form id="qrLoginForm"><input name="uuid" value="template"><img id="qr_img" src="/authserver/qrCode/getCode?uuid=template">
    <div id="qr_invalid">Expired template</div></form></div>
  <input type="password">
  <form id="qrLoginForm" method="post" action="/authserver/login?display=qrLogin&amp;service=fixture">
    <input name="uuid" value="current"><input name="execution" value="fixture-cas">
    <input name="_eventId" value="submit"><img id="qr_img" src="/authserver/qrCode/getCode?uuid=current">
    <div id="qr_invalid" style="display:none">Expired</div><div id="qr_success" style="display:none">Scanned</div></form>
  <script>
    const prefilters = [];
    window.fixture = { requests: [], refreshes: 0, polls: 0, submits: 0 };
    window.jQuery = { ajaxPrefilter: callback => prefilters.push(callback) };
    window.send = function(url, success) {
      const callbacks = {done: [], fail: [], always: []};
      const options = {url, success};
      const request = {
        status: 0, settled: false,
        done(fn) { callbacks.done.push(fn); return this; },
        fail(fn) { callbacks.fail.push(fn); return this; },
        always(fn) { callbacks.always.push(fn); return this; },
        abort() { this.reject(0, 'abort'); },
        resolve(data) {
          if (this.settled) return;
          this.settled = true;
          callbacks.done.forEach(fn => fn(data));
          options.success?.(data, 'success');
          callbacks.always.forEach(fn => fn());
        },
        reject(code, reason) {
          if (this.settled) return;
          this.settled = true; this.status = code;
          callbacks.fail.forEach(fn => fn(this, reason));
          callbacks.always.forEach(fn => fn());
        }
      };
      prefilters.forEach(fn => fn(options, {}, request));
      fixture.requests.push({options, request});
      return request;
    };
    window.QR_LOGIN_ENABLED = 0;
    window.countDown = () => { fixture.polls++; };
    window.isUsed = () => send('/authserver/qrCode/getStatus.htl?ts=fixture', status => {
      if (status === '1') fixture.submits++;
      if (status === '2') document.querySelector('form:not(#qrLoginDiv form) #qr_success').style.display = 'block';
      if (status === '3') document.querySelector('form:not(#qrLoginDiv form) #qr_invalid').style.display = 'block';
    });
    window.getQrCode = () => send('/authserver/qrCode/getToken?ts=fixture', token => {
      document.querySelector('form:not(#qrLoginDiv form) #qr_img').src = '/authserver/qrCode/getCode?uuid=' + token;
      document.querySelector('form:not(#qrLoginDiv form) input[name=uuid]').value = token;
      countDown();
    });
    window.refresh = () => {
      fixture.refreshes++;
      getQrCode();
      const form = document.querySelector('form:not(#qrLoginDiv form)');
      form.querySelector('#qr_invalid').style.display = 'none';
      form.querySelector('#qr_success').style.display = 'none';
    };
  </script></body></html>`;

async function setup(t) {
  const context = await browser.newContext();
  t.after(() => context.close());
  const counts = { pages: 0, images: 0, posts: [], cookiePreserved: false };
  await context.addCookies([{name: 'qr-fixture-session', value: 'same-session', url: 'https://ids.njust.edu.cn', httpOnly: true, secure: true}]);
  await context.route('**/*', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/authserver/login') {
      counts.pages++;
      if (route.request().method() === 'POST') {
        counts.posts.push(route.request().postData());
        counts.cookiePreserved = (await route.request().allHeaders()).cookie?.includes('qr-fixture-session=same-session') === true;
        await route.fulfill({contentType: 'text/html', body: '<table id="kbtable"></table>'});
      } else await route.fulfill({ contentType: 'text/html', body: fixture });
    } else if (path === '/authserver/qrCode/getCode') {
      counts.images++;
      await route.fulfill({ contentType: 'image/png', body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQkAAAAASUVORK5CYII=', 'base64') });
    } else await route.abort();
  });
  const page = await context.newPage();
  await page.goto('https://ids.njust.edu.cn/authserver/login');
  const run = (action, displayedSource = '') => page.evaluate(({ source, action, displayedSource }) =>
    JSON.parse((0, eval)('(' + source + ')')(action, displayedSource)), { source, action, displayedSource });
  return { page, run, counts };
}

test('reuse the loaded live QR image, ignoring the hidden duplicate form, without another request', async t => {
  const { page, run, counts } = await setup(t);
  assert.deepEqual(await run('prepare'), { state: 'prepared', passwordReady: true });
  const before = { ...counts };
  const image = await run('start');
  assert.equal(image.state, 'waiting');
  assert.match(image.imageUrl, /uuid=current$/);
  assert.match(image.dataUrl, /^data:image\/png;base64,/);
  assert.deepEqual(counts, before);
  assert.deepEqual(await run('inspect', image.imageUrl), { state: 'waiting' });
  assert.equal(await page.evaluate(() => fixture.polls), 0);
  assert.equal(await page.evaluate(() => fixture.requests.length), 1); // Same-session status check only.
});

test('expired code refresh uses the school button function, clears the old image and renews only once', async t => {
  const { page, run, counts } = await setup(t);
  await run('prepare');
  const oldImage = await run('start');
  await page.evaluate(() => document.querySelector('form:not(#qrLoginDiv form) #qr_invalid').style.display = 'block');
  assert.equal((await run('inspect')).state, 'expired');
  assert.equal((await run('refresh')).refreshAccepted, true);
  assert.equal((await run('refresh')).state, 'pending_token');
  assert.equal(await page.evaluate(() => fixture.refreshes), 1);
  assert.equal(await page.evaluate(() => document.querySelector('form:not(#qrLoginDiv form) #qr_img').getAttribute('src')), null);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('replacement'));
  await page.waitForFunction(() => document.querySelector('form:not(#qrLoginDiv form) #qr_img').naturalWidth > 0);
  const next = await run('inspect');
  assert.equal(next.state, 'waiting');
  assert.notEqual(next.imageUrl, oldImage.imageUrl);
  assert.equal(counts.pages, 1);
  assert.equal(counts.images, 3); // Two initial images (including template), one replacement.
});

test('preparing a password form stops QR login; pending image/token creation can still finish', async t => {
  const { page, run } = await setup(t);
  await run('prepare');
  await page.evaluate(() => getQrCode());
  await run('prepare');
  assert.equal(await page.evaluate(() => fixture.requests.at(-1).request.settled), false);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('prepared'));
  assert.equal(await page.evaluate(() => fixture.polls), 0);
  await page.evaluate(() => send('/authserver/qrCode/getStatus.htl', () => fixture.submits++).resolve('1'));
  assert.equal(await page.evaluate(() => fixture.submits), 0);
  await run('start');
  await page.evaluate(() => send('/authserver/qrCode/getStatus.htl', () => fixture.submits++));
  await run('pause');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('1'));
  assert.equal(await page.evaluate(() => fixture.submits), 0);
});

test('HTTP, timeout, empty-token and status failures are distinguishable and manual refresh recovers', async t => {
  const { page, run } = await setup(t);
  await run('prepare');
  await run('start');
  for (const error of [
    { phase: 'token', kind: 'http', code: 503 },
    { phase: 'token', kind: 'timeout', code: 0 },
    { phase: 'token', kind: 'empty' },
    { phase: 'status', kind: 'network', code: 0 }
  ]) {
    await run('refresh');
    if (error.phase === 'status') {
      await page.evaluate(() => fixture.requests.at(-1).request.resolve('retry'));
      await page.evaluate(() => send('/authserver/qrCode/getStatus.htl', () => {}));
    }
    await page.evaluate(error => {
      const {request, options} = fixture.requests.at(-1);
      if (error.phase === 'token') {
        if (options.timeout !== 12000) throw Error('Token request has no timeout');
      }
      if (error.kind === 'empty') request.resolve('');
      else request.reject(error.code, error.kind === 'timeout' ? 'timeout' : 'error');
    }, error);
    const result = await run('inspect');
    assert.equal(result.state, 'request_error');
    assert.deepEqual(result.error, error);
  }
  assert.equal((await run('refresh')).refreshAccepted, true);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('recovered'));
  await page.waitForFunction(() => document.querySelector('form:not(#qrLoginDiv form) #qr_img').naturalWidth > 0);
  assert.equal((await run('inspect')).state, 'waiting');
});

test('WeChat confirmation on return is observed without timers and posts the live QR form once with the same cookie', async t => {
  const {page, run, counts} = await setup(t);
  await run('prepare');
  await run('start');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('2'));
  assert.deepEqual(await run('inspect'), {statusCode: '2', state: 'scanned'});
  const before = {...counts};
  await run('resume'); // No website setTimeout/countDown is needed to discover confirmation.
  assert.equal(await page.evaluate(() => fixture.requests.length), 2);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('\uFEFF 1 \n'));
  assert.deepEqual(await run('inspect'), {statusCode: '1', state: 'confirmed'});
  assert.equal(await page.evaluate(() => fixture.submits), 0); // Do not also run the duplicate-ID submit handler.
  assert.equal((await run('resume')).state, 'confirmed');
  assert.equal(counts.pages, before.pages);
  assert.equal(counts.images, before.images);

  const submitted = await page.evaluate(source => {
    const command = (0, eval)('(' + source + ')');
    return [JSON.parse(command('submit', '')), JSON.parse(command('submit', ''))];
  }, source);
  assert.deepEqual(submitted.map(item => item.state), ['submitting', 'submitting']);
  await page.waitForSelector('#kbtable', {state: 'attached'});
  assert.equal(counts.posts.length, 1);
  const fields = new URLSearchParams(counts.posts[0]);
  assert.equal(fields.get('uuid'), 'current'); // Hidden duplicate form contains "template".
  assert.equal(fields.get('execution'), 'fixture-cas');
  assert.equal(fields.get('_eventId'), 'submit');
  assert.equal(fields.has('password'), false);
  assert.equal(counts.cookiePreserved, true);
});

test('returning to the foreground replaces a suspended status request, keeps one request in flight and respects cadence', async t => {
  const {page, run} = await setup(t);
  await page.evaluate(() => { fixture.now = Date.now(); Date.now = () => fixture.now; });
  await run('prepare');
  await run('start');
  for (let i=0; i<5; i++) await run('inspect');
  assert.equal(await page.evaluate(() => fixture.requests.length), 1);
  await run('resume');
  assert.equal(await page.evaluate(() => fixture.requests[0].request.settled), true);
  assert.equal(await page.evaluate(() => fixture.requests.length), 2);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('0'));
  await run('inspect');
  assert.equal(await page.evaluate(() => fixture.requests.length), 2);
  await page.evaluate(() => { fixture.now += 1000; });
  await run('inspect');
  assert.equal(await page.evaluate(() => fixture.requests.length), 3);
  await page.evaluate(() => fixture.requests.at(-1).request.reject(0, 'timeout'));
  await run('resume'); // A background timeout must not prevent a fresh foreground check.
  assert.equal(await page.evaluate(() => fixture.requests.length), 4);
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('2'));
  assert.equal((await run('inspect')).state, 'scanned');
  assert.equal(await page.evaluate(() => fixture.requests.some(item => item.options.url.includes('getToken'))), false);
});

test('unknown status, expiry and cancelled confirmation never submit credentials or a QR form', async t => {
  const {page, run, counts} = await setup(t);
  await run('prepare');
  await run('start');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('<html>login failed</html>'));
  const invalid = await run('inspect');
  assert.equal(invalid.state, 'request_error');
  assert.deepEqual(invalid.error, {phase: 'status', kind: 'unexpected'});

  await run('refresh');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('new-code'));
  await run('resume');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('3'));
  assert.deepEqual(await run('inspect'), {statusCode: '3', state: 'expired'});

  await run('refresh');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('another-code'));
  await run('resume');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('1'));
  await run('pause');
  assert.equal((await run('submit')).state, 'submit_error');
  assert.equal(counts.posts.length, 0);
});

test('confirmed login validates the live form method, UUID and destination before submitting', async t => {
  const {page, run, counts} = await setup(t);
  await run('prepare');
  await run('start');
  await page.evaluate(() => fixture.requests.at(-1).request.resolve('1'));
  for (const invalid of ['destination', 'method', 'uuid']) {
    await page.evaluate(invalid => {
      const form = document.querySelector('form:not(#qrLoginDiv form)');
      form.action = invalid === 'destination' ? 'https://unexpected.example/authserver/login' : '/authserver/login';
      form.method = invalid === 'method' ? 'get' : 'post';
      form.querySelector('[name=uuid]').value = invalid === 'uuid' ? '' : 'current';
    }, invalid);
    assert.equal((await run('submit')).state, 'submit_error');
  }
  assert.equal(counts.posts.length, 0);
});
