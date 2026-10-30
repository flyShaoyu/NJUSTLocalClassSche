import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { access, mkdir, open, readFile, unlink } from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { Browser, BrowserContext, chromium } from "playwright";

const exists = async (file: string): Promise<boolean> => access(file).then(() => true, () => false);

export const findPublicBrowser = async (): Promise<string> => {
  if (process.platform !== "win32") throw new Error("公开网页读取目前支持 Windows 上已安装的 Edge / Chrome");
  const candidates = [
    path.join(process.env["ProgramFiles(x86)"] || "C:/Program Files (x86)", "Microsoft/Edge/Application/msedge.exe"),
    path.join(process.env.ProgramFiles || "C:/Program Files", "Microsoft/Edge/Application/msedge.exe"),
    path.join(process.env.ProgramFiles || "C:/Program Files", "Google/Chrome/Application/chrome.exe"),
    path.join(process.env.LOCALAPPDATA || "", "Google/Chrome/Application/chrome.exe")
  ];
  for (const candidate of candidates) if (await exists(candidate)) return candidate;
  throw new Error("未找到已安装的 Edge / Chrome，请安装浏览器，或使用 --file 导入正常浏览器保存的网页");
};

const reserveLocalPort = async (): Promise<number> => {
  const server = net.createServer();
  await new Promise<void>((resolve, reject) => server.once("error", reject).listen(0, "127.0.0.1", resolve));
  const address = server.address();
  if (!address || typeof address === "string") throw new Error("无法分配本机浏览器连接端口");
  const port = address.port;
  await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  return port;
};

/** A project-owned profile only; never opens the user's everyday browser profile. */
export const launchPublicSiteBrowser = async (): Promise<{
  browser: Browser;
  context: BrowserContext;
  close: () => Promise<void>;
}> => {
  const executable = await findPublicBrowser();
  const profile = path.resolve("artifacts/jwc-source/standard-edge-profile");
  await mkdir(profile, { recursive: true });
  const lockPath = path.join(profile, ".classsche-reader.lock");
  const lockToken = randomUUID();
  let lock;
  try {
    lock = await open(lockPath, "wx");
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    // Recover only a lock whose owning process has exited; keep unknown locks.
    const previous = await readFile(lockPath, "utf8").then(text => JSON.parse(text) as { pid?: number }).catch(() => null);
    let exited = false;
    if (Number.isInteger(previous?.pid) && previous!.pid! > 0) {
      try { process.kill(previous!.pid!, 0); }
      catch (failure) { exited = (failure as NodeJS.ErrnoException).code === "ESRCH"; }
    }
    if (!exited) throw new Error("公开网页读取正在进行，或会话锁无法确认；请先结束上一次读取任务");
    await unlink(lockPath);
    lock = await open(lockPath, "wx");
  }
  await lock.writeFile(JSON.stringify({ pid: process.pid, token: lockToken }));
  await lock.close();
  const releaseLock = async () => {
    const current = await readFile(lockPath, "utf8").then(text => JSON.parse(text) as { token?: string }).catch(() => null);
    if (current?.token === lockToken) await unlink(lockPath).catch(() => {});
  };
  let browser: Browser | undefined;
  let child: ReturnType<typeof spawn> | undefined;
  try {
    const port = await reserveLocalPort();
    // Run the installed browser normally; CDP only controls this isolated session.
    // No user-agent overrides, JS environment patches, or disabled protections.
    child = spawn(executable, [
      `--user-data-dir=${profile}`, `--remote-debugging-port=${port}`,
      "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check", "about:blank"
    ], { windowsHide: true, stdio: "ignore" });
    let startupError: Error | undefined;
    child.on("error", error => { startupError = error; });
    const deadline = Date.now() + 15000;
    while (Date.now() < deadline) {
      if (startupError) throw startupError;
      if (child.exitCode !== null) throw new Error(`浏览器提前退出（代码 ${child.exitCode}），请关闭项目专用浏览器后重试`);
      try { browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`, { timeout: 1500 }); break; }
      catch { await new Promise(resolve => setTimeout(resolve, 250)); }
    }
    if (!browser) throw new Error("连接本机公开网页浏览器超时，原缓存未覆盖");
    const context = browser.contexts()[0];
    if (!context) throw new Error("浏览器会话初始化失败");
    let closed = false;
    const close = async () => {
      if (closed) return;
      closed = true;
      try {
        // Close only the browser process/profile created by this command.
        await (await browser!.newBrowserCDPSession()).send("Browser.close").catch(() => {});
        await browser!.close().catch(() => {});
      } finally { await releaseLock(); }
    };
    return { browser, context, close };
  } catch (error) {
    if (browser) {
      await (await browser.newBrowserCDPSession()).send("Browser.close").catch(() => {});
      await browser.close().catch(() => {});
    } else child?.kill();
    await releaseLock();
    throw error;
  }
};
