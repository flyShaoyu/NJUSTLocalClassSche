# 统一认证与教务入口迁移记录

本记录描述 2026-09-29 的本地验证和当前实现。它不是学校网站的长期可用性保证；入口变化时应重新检查。

## 当前入口与凭据

- 统一认证：`https://ids.njust.edu.cn/authserver/login?service=https%3A%2F%2Fehall2.njust.edu.cn%2Flogin`
- 教务 SSO：`https://bkjw.njust.edu.cn/njlgdx/indexsso.jsp`
- 课表直达：`https://bkjw.njust.edu.cn/njlgdx/xskb/xskb_list.do`
- `.env` 中使用 `USERNAME` 和单一的 `PASSWORD`；`PASSWORD` 是**智慧理工服务门户密码**。不要在文档、日志或提交中保存实际值。

只登录门户不等于已登录教务。访问教务 SSO 后，校方会为教务域名建立会话。旧 IP 的 Cookie 不能直接改域名迁移；桌面端继续用 Playwright `storageState.json` 复用登录态，Android 端继续用 WebView `CookieManager`。两端不直接共享 Cookie 文件。

## 2026-09-29 页面检查

课表 URL 有时会返回 HTTP 200 的旧登录表单，地址仍是课表路径。代码因此同时检查登录表单、跳转目标和 `#kbtable` 等业务内容；缺少有效表格时不能覆盖原缓存。考试、成绩、等级考试同样检查页面结构。Android 纯 HTTP 登录需按 CAS 表单要求提交，额外验证由用户在认证网页完成。

当时完整桌面抓取、保存后复用登录态、五类生成页面加载和 Android APK 构建通过。没有连接手机或模拟器，因此 WebView 交互、验证码和后台定时任务未做设备端端到端验证。实际密码、完整 Cookie 与临时票据均未写入本记录；调试文件位于被 Git 忽略的 `artifacts/`。
