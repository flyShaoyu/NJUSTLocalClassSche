# ClassSche

面向南京理工大学教务系统的课表、考试和成绩本地查看工具。桌面端使用 Node.js、TypeScript 与 Playwright 抓取和生成页面；Android 端负责登录、缓存展示、通知和后台同步。

该项目目标链路为：

1. 通过统一身份认证进入教务系统。
2. 抓取课表、考试安排、成绩和等级考试页面。
3. 解析为结构化 JSON，并生成本地页面。
4. 将不含个人数据的页面模板和公共资源导出到 Android，再构建 APK。

## 发布下载

<div style="display:flex;flex-wrap:wrap;gap:12px;align-items:center;">
  <a href="https://github.com/flyShaoyu/NJUSTLocalClassSche/releases" style="display:inline-block;padding:10px 16px;border-radius:10px;background:#24292f;color:#ffffff;text-decoration:none;font-weight:600;line-height:1;box-shadow:0 6px 16px rgba(36,41,47,0.18);">
    GitHub Releases
  </a>
  <a href="https://gitee.com/flyshaoyu/njust_localclasssche/releases" style="display:inline-block;padding:10px 16px;border-radius:10px;background:#c71d23;color:#ffffff;text-decoration:none;font-weight:600;line-height:1;box-shadow:0 6px 16px rgba(199,29,35,0.18);">
    Gitee Releases
  </a>
</div>


- GitHub Releases：
  [https://github.com/flyShaoyu/NJUSTLocalClassSche/releases](https://github.com/flyShaoyu/NJUSTLocalClassSche/releases)

- Gitee Releases：
  [https://gitee.com/flyshaoyu/njust_localclasssche/releases](https://gitee.com/flyshaoyu/njust_localclasssche/releases)

## 当前能力

- 桌面端使用教务SSO进行登录。
- 抓取并解析课表、考试安排、成绩和等级考试，生成对应本地页面；课表支持周视图、全学期视图、冲突课程和学分。
- Android 端提供原生首页、WebView 登录、本地缓存、多学期课表、课程与考试通知、成绩统计及新成绩提醒。

## 目录结构

按功能查找源码和配置请看 [项目文件索引](FILE-INDEX.md)。

- `src/`
  核心脚本，包含登录、抓取、解析、渲染、Android 导出逻辑
- `android/`
  Android 工程

## 环境要求

- Node.js 20+
- npm
- Playwright Chromium
- Android Studio / Android SDK
- JDK 17

## 初次使用

1. 安装依赖

```bash
npm install
npx playwright install chromium
```

2. 复制环境变量模板

```powershell
Copy-Item .env.example .env
```

3. 按需修改 `.env`

- `USERNAME`：学号或统一身份认证用户名。
- `PASSWORD`：智慧理工服务门户密码；留空时可在浏览器内手工完成认证。
- `SEMESTER`：可选的考试查询学期；留空时采用网站当前选择。

## 常用命令

抓取课表、考试、成绩和等级考试并更新本地产物：

```bash
npm run start
```

单独抓取：`npm run fetch:exams`、`npm run fetch:scores`、`npm run fetch:level-exams`。

仅重解析已有 HTML：

```bash
npm run parse
```

根据已有 JSON 重绘本地页面：

```bash
npm run render:ui
```

导出 Android 资源：

```bash
npm run export:android
```

类型检查：

```bash
npm run check
```

编译和回归测试：`npm run build`，然后运行 `node --test tests/*.test.mjs`。

## Android 构建

`gradlew.bat` 在 `android/` 目录下，不在仓库根目录。

推荐命令：

在 `android/` 目录使用 JDK 17 和已安装的 Android SDK 运行 `.\gradlew.bat :app:assembleDebug`。调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。

## 产物说明

- `artifacts/timetable.html`
  原始课表 HTML
- `artifacts/timetable.json`
  解析后的结构化课表
- `artifacts/timetable-view.html`
  本地课表页面
- `artifacts/home-view.html`
  本地首页页面
- `artifacts/exam-list.json`、`score-list.json`、`level-exam-list.json`
  考试、成绩和等级考试解析结果
- `artifacts/storageState.json`
  Playwright 登录态

`artifacts/` 是本机生成目录，不应提交。`export:android` 只写入空数据页面模板、公共图片和资源更新元数据；个人课表、考试及成绩数据保留在本机运行时缓存。

## 串行执行说明

这一步很重要：`render:ui` 和 `export:android` 不能并行跑。

正确顺序是：

1. `npm run render:ui`
2. `npm run export:android`
3. `./gradlew.bat :app:assembleDebug`

原因是 `export:android` 依赖 `render:ui` 刚生成的新 HTML。若并行执行，Android 很容易打进上一个版本的页面，表现为“网页是新的，app 落后一版”。

## 登录与缓存

桌面端：

1. 优先复用 `artifacts/storageState.json`
2. 教务会话失效时，先通过 `https://bkjw.njust.edu.cn/njlgdx/indexsso.jsp` 兑换已有统一认证会话
3. 需要重新登录时，打开 `ids.njust.edu.cn`；有 `USERNAME` 和 `PASSWORD` 时尝试提交，验证码或额外验证由用户在浏览器内完成
4. 统一认证通过后再访问教务 SSO 入口，确认业务表格有效后保存登录态
5. 课表、考试安排、成绩、等级考试继续共用同一个浏览器上下文和 Cookie

Android 端：

1. 本地登录页输入账号和统一身份认证密码
2. 隐藏 `WebView` 通过教务 SSO 入口打开真实认证页，点击校方按钮完成加密提交
3. 登录页不再显示本地验证码输入或识别；校方要求额外验证时，在同一个 WebView 会话内完成网页验证
4. 继续使用 `CookieManager` 将教务域名自己的 Cookie 带入 HTTP 抓取，不混合不同域名的同名 Cookie
5. 后台同步优先复用 WebView 会话；需要重新认证时使用 CAS 加密表单，遇到额外验证则提示用户在认证网页完成
6. 仅在确认 `#kbtable` 等业务内容存在后更新本地缓存

Android 本地保存密码时使用 Android Keystore 中的不可导出密钥进行 AES-GCM 加密。已有安装中旧版保存的明文密码会在首次读取时迁移到加密字段并删除原字段；应用已关闭系统备份，避免凭据随备份文件离开设备。

“我的”中的“检查更新”会缓存 24 小时内的检查结果，包括发现新版本时的下载地址和更新简介；再次点击可查看结果，并在弹窗中选择“重新获取更新”立即重新联网检查。
安装包交由 Android 系统下载管理器处理；应用切到后台后仍会继续下载，返回时重新显示下载进度。下载弹窗右上角的叉叉可取消任务并清理未完成的安装包。

当前站点入口、业务页验证记录和登录态限制见 [登录迁移记录](LOGIN-MIGRATION.md)。排错时请保留具体步骤及错误类型（超时、HTTP 状态码、跳转或解析），不要在日志或问题报告中贴出密码与 Cookie。

## 安全说明

- `.env`
- `artifacts/`
- Android 构建产物
- APK
- 登录态

以上都不应提交到 git。

## 相关文档

- [ANDROID.md](./ANDROID.md)
- [API.md](./API.md)
- [AGENT.md](./AGENT.md)
- [PROMPTS.md](./PROMPTS.md)
- [FILE-INDEX.md](./FILE-INDEX.md)
- [LOGIN-MIGRATION.md](./LOGIN-MIGRATION.md)

## 写在最后
 - 本仓库完全由codex生成，几乎无人工痕迹
 - 本app UI来源于周三课表，(就是抄的)
 - 目前仅支持NJUST，有问题提issue，欢迎pr，我(codex)会审的
 - 没了

## P.S. 留一点你们可能会喜欢的项目
 - https://github.com/Samueli924/chaoxing
 - https://github.com/aquamarine5/ChaoxingSignFaker
 - https://github.com/VermiIIi0n/fuckZHS
