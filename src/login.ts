import { Page } from "playwright";
import { AppConfig } from "./types.js";
import { logStep } from "./logger.js";

export const isIdentityLogin = (url: string): boolean => {
  const parsed = new URL(url);
  return parsed.hostname === "ids.njust.edu.cn" && parsed.pathname.startsWith("/authserver/");
};

export const looksLikeLoginPage = async (page: Page): Promise<boolean> =>
  isIdentityLogin(page.url()) || await page.locator("input[type='password']:visible").count() > 0;

export const waitForManualLogin = async (page: Page, config: AppConfig): Promise<void> => {
  const identityLogin = isIdentityLogin(page.url());
  const username = page.locator("#username:visible, #xh:visible, input[name='USERNAME']:visible").first();
  const password = page.locator("input[type='password']:visible").first();
  const savedPassword = config.password;
  if (config.username && await username.count()) await username.fill(config.username);
  if (savedPassword && await password.count()) await password.fill(savedPassword);

  // Use the university's encryption handler. Submit once, leaving challenges manual.
  const submit = page.locator("#login_submit:visible").first();
  if (identityLogin && config.username && savedPassword && await submit.count()) {
    logStep("Submitting unified identity login using PASSWORD.");
    await submit.click();
  }
  logStep("Waiting for authenticated landing page. Complete any verification in the browser.");
  const deadline = Date.now() + config.manualLoginTimeoutMs;
  while (Date.now() < deadline) {
    if (page.isClosed()) throw new Error("登录中断：认证窗口在完成登录前被关闭。");
    const url = new URL(page.url());
    if (!await looksLikeLoginPage(page)) {
      const portal = url.hostname === "ehall2.njust.edu.cn" && url.pathname === "/index.html";
      const teaching = url.hostname === "bkjw.njust.edu.cn" && url.pathname === "/njlgdx/framework/main.jsp";
      const table = await page.locator("#kbtable, #dataList").count() > 0;
      const configured = config.loginSuccessSelector &&
        await page.locator(config.loginSuccessSelector).first().isVisible();
      if (portal || teaching || table || configured) return;
    }
    await page.waitForTimeout(500);
  }
  throw new Error(`登录超时：${config.manualLoginTimeoutMs} 毫秒内未进入认证后的页面；当前地址 ${page.url()}。请检查密码、验证码或网站提示。`);
};
