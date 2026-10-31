# ClassSche

面向南京理工大学教务系统的课表、考试和成绩本地查看工具。桌面端使用 Node.js、TypeScript 与 Playwright 抓取和生成页面；Android 端负责登录、缓存展示、通知和后台同步。

该项目目标链路为：

1. 通过统一身份认证进入教务系统。
2. 抓取课表、考试安排、成绩和等级考试页面。
3. 解析为结构化 JSON，并生成本地页面。
4. 将不含个人数据的页面模板和公共资源导出到 Android，再构建 APK。

## 发布下载

维护者本机的发布工具集中在 `release-tools/`，整个目录被 Git 忽略，不随仓库分发。本机可编辑 `release-tools/release-notes.md` 后运行 `npm run release`，一次发布到 GitHub 与 Gitee；配置、凭据和使用说明均在该目录，支持 `--version`、`--dry-run` 和 `--resume`。

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
- 首页已接入添加日程、空闲教室、常用网站、图书馆和“小工具”；“小工具”合并课程搜索与课程评教，教师课表可由课程搜索进入。图书搜索、我的借阅与本机收藏合并在图书馆页面。
- 本地日程保留学期、周次和星期，支持自由起止时间（可跨课间）、选填地点与备注，直接显示在个人课表中，可编辑和删除；不上传服务器。
- 成绩页新增“通识选修学分统计”，按校方 2022 版分类表汇总已通过课程的学分，顶部显示总学分及各类别学分，标题栏在原页切换分类编辑状态，可长按课程块或拖无背景三条杠跨分类移动，删除后移至待分类，只有待分类提供下拉框，一次保存后保留本机记录；计划外纳入候选，辅修由用户手动指定，重复课程仅计一次，未收录和冲突单独待确认。分类表可按名称和课程号搜索。可在手机点击“更新分类表”，由 App WebView 正常完成校验、读取并缓存校方最新目录。
- `npm run fetch:general-education` 使用本机普通 Edge/Chrome 与项目独立会话读取教务处公开分类表，等待校方校验后读取正文；也支持 `--file` 导入保存网页，防护失败不会覆盖课程目录。
- `npm run verify:home` 保留首页十项的数据流程验证。命令与范围见 [API.md](API.md)，学校页面实测与 Android 接入范围见 [FEATURE-AUDIT.md](FEATURE-AUDIT.md)。
- Android 模拟器回归覆盖实际工具页桥接、成绩/分类入口、统一顶栏、日程与学校查询；设备测试命令见 [ANDROID.md](ANDROID.md)。同步会忽略校方无效的 `Domain=edu.cn` 语言 Cookie，手动更新结束后显示明确结果。
- “小工具 → 课程评教”支持配置普通题/最后一题的选项和评语，读取逐门预览后确认批量提交，沿用教务 Cookie；逐门核对校方完成状态，结果不明时停止且不自动重试。实现与验证范围见 [ANDROID.md](ANDROID.md#课程评教)。
- “小工具 → 选课 / 抢课”已可打开通识课程分类目录，与成绩页共用分类数据及本机更正，支持课程名/课程号搜索、分类筛选和分页。当前目录不代表本学期可选课程；选退课提交与抢课任务仍关闭，待教务开放后再适配。

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

1. 每次进入本地登录界面只重载认证表单并保留已有 Cookie，不自动登录、进入课表或更新缓存；点击“提交登录”或“微信扫码登录”后才继续认证。扫码弹窗可将当前二维码保存到相册“周三课表”，保存时不关闭弹窗或重新获取二维码
2. 隐藏 `WebView` 使用电脑端 User-Agent 打开真实认证表单，点击校方按钮完成加密提交；微信扫码直接读取当前网页已加载的二维码，不重复加载整页或下载图片。过期后点击“重新获取”，调用学校网页的刷新入口申请新码，并沿用同一会话等待确认和后续跳转；二维码申请、图片加载、状态检查分别提示超时、HTTP 或解析错误
   扫码状态 `2` 仅表示已扫码，`1` 才是微信确认成功。返回 App 时立即检查原二维码状态；前台检查不依赖隐藏网页定时器，确认后只提交一次校方二维码表单并继续教务 SSO，保留原 Cookie。认证和教务跳转分别显示进度及超时，运行日志中的 `QR_AUTH` 可查看具体状态码。
3. 登录失败时优先显示校方网页的错误提示（例如 HTTP 401 页面中的账号密码提示），不再将找到输入框和点击按钮的结果当作验证成功
   账号密码和扫码共用认证后的中转逻辑，支持门户 `/index.html` 与 `/default/index.html` 等正常落点；继续复用现有 Cookie 建立教务会话，再获取课表，不因进入门户重新提交密码。`AUTH_FLOW` 日志显示后续跳转步骤。
4. 校方要求图形验证码时，登录页显示当前认证会话的验证码图片，用户手动填写，也可以刷新图片；不包含 OCR 库或识别模型
5. 后台成绩同步和课表更新优先读取 WebView 保存的 Cookie。教务 Cookie 有效时直接抓取；教务会话失效后先用统一认证 Cookie 恢复 SSO，两者均失效后才提交一次账号密码。HTTP 返回的原始 `Set-Cookie`（含更新和删除）按原站点、路径、有效期及安全属性写回 WebView 并落盘，供下一次同步复用，不混合不同域名的同名 Cookie
6. 更新课表需要图形验证码时，跳转到登录页手动填写，提交后继续更新；超时、HTTP 5xx 或网页解析错误不会当成会话失效去提交密码，失败保留原缓存
7. 仅在确认 `#kbtable` 等业务内容存在后更新本地缓存

Android 本地保存密码时使用 Android Keystore 中的不可导出密钥进行 AES-GCM 加密。已有安装中旧版保存的明文密码会在首次读取时迁移到加密字段并删除原字段；应用已关闭系统备份，避免凭据随备份文件离开设备。

每个进程首次打开主界面时自动检查一次成绩，不受后台定时间隔限制；进程仍在后台存活时切回或重建主界面不重复触发，结束进程后重新打开再检查一次。后台定时检查仍使用设置中的间隔，默认 2 小时；最近一次尝试时间持久保存，启动同步也会重置下次定时检查的起算点。关闭同步、仅 Wi-Fi 和认证失败暂停同样生效；学校拒绝认证或要求验证码时暂停自动认证，手动登录成功后恢复。首页“更新数据”沿用原刷新图标，手动同步课表及相关缓存。日志中的 `HEADLESS_SESSION` 可区分 Cookie 复用与会话失效，`HEADLESS_LOGIN` 会注明实际密码提交。

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


2026-10-30：本人课表叠加考试日期、时间、教室及座位；新建日程可全选周次。图书搜索已适配学校新版 OPAC，借阅缺少登录时可进入官方图书馆登录页，再返回刷新。搜索置顶、按钮无背景，常用网站支持复制当前链接及外部网页双指缩放。此次验收记录见 `artifacts/ui-refinement-20261030/`，功能边界见 `ANDROID.md`。


本轮补充（2026-10-30）：学分统计搜索恢复与卡片一致的左右边距，保留直角；图书搜索位于三个切换标签下。空闲教室支持多个大节同时查询，只返回全部已选时段均空闲的交集；未选中的间隔不参与查询。筛选及结果优先一至四教学楼，结果不显示人数。


2026-10-30 详情整理：自建日程连续周次合并为区间；考试场次编号不再显示为备注，座位单列在地点后，日程及考试标签使用“周数”。图书卡片压紧，首页入口恢复“图书搜索”；借阅因真实请求持续登录失效，按用户要求移除入口，搜索及收藏保留。首页去掉旧的额外10dp底部留白。考试安排默认只展开最近未结束的一场，全部结束则全部收起。


图书馆登录补充：搜索页保留“我的图书馆”只读入口，提供独立登录、官网首页四栏状态和“打开官网”。不在应用内进行借阅/续借等操作；官网复用WebView登录态。状态、个人资料不写入应用文件或测试资料；图书馆账密仅在本机通过Keystore加密保存。


图书馆登录现使用原生本地登录框：登录类型、账号、密码、验证码与换一张；验证码需本人填写。提交沿用官网原表单编码，登录成功后单独加密保存账密和登录类型，验证码不保存。首页四栏为超期图书、委托到书、预约到书、荐购图书，官网空值显示“—”。真实四栏及登录态复用已在模拟器验证，未保存真实值或个人页面。


图书馆状态页直接展示四栏，底部提供刷新、官网和登录入口。本地图书馆密码框提示默认密码为身份证后六位；登录成功后加密保存实际账密，下次自动填入。课表支持30色并尽量避免同周同色/近色；学分分类支持触点跟随拖动。


分类编辑新增“恢复默认”：仅重置当前草稿，保存后回到校方目录/课程原始信息的自动分类；返回可放弃。课表日程编辑改为小号无背景入口，详情可滚动，底部圆点保持可见。


预览版发布：`npm run release -- --version 1.1.0 --prerelease`（生成alpha.N）。现有本机发布工具说明及预览/续传操作见`release-tools/README.md`；发布工具与凭据目录保持Git忽略。


### 学校邮箱（本地开发版）
从首页“首页 → 邮件管理”进入。网页版开启IMAP/SMTP并生成客户端专用密码后，在原生设置填写完整邮箱和授权码，验证连接并加密保存。支持邮件夹、每页30封、纯文字正文、写信/回复和发送状态记录；附件首版只显示名称，不下载，HTML转文字，不加载外链图片。账号与授权码不进入网页桥接或模型。
新安装默认每15分钟检查收件并开启新邮件通知，可选关闭，或15/30分钟、1/2/5/10/20小时、1/2/5天，设置后由Android JobScheduler在联网时执行，待机/省电可能延迟。首次建立基线，不把旧信全当新信；通知只显示新信数量。设置页显示最近轮询结果。清除配置只移除本机密文并停轮询，不删除服务器邮件。
仅SMTP返回成功才记“服务器已受理”，不等于已送达或已读；未知结果不会自动重发。配置未验证通过不保存。客户端授权码须在本人手机上配置，不随APK或Git分发。
