# 内部脚本与数据接口

这里的“API”是仓库内的命令、模块和 JSON 文件约定，不是公开 HTTP 服务。命令从仓库根目录运行，具体脚本以 [`package.json`](package.json) 为准。

## 命令入口

| 命令 | 输入与结果 |
| --- | --- |
| `npm run start` | `src/index.ts`：复用登录态或完成统一认证，依次抓取课表、考试安排、成绩和等级考试，生成 `artifacts/` 中的 HTML、JSON 与页面。 |
| `npm run fetch:exams` | 只进入考试安排页，保存原始 `exam-list.html`；需要结构化数据时继续运行 `npm run parse` 或完整抓取。 |
| `npm run fetch:scores` | 单独保存成绩原始 HTML、解析 JSON 与截图。 |
| `npm run fetch:level-exams` | 单独保存等级考试原始 HTML、解析 JSON 与截图。 |
| `npm run fetch:general-education` | 普通浏览器进程与项目独立会话读取教务处公开分类表，输出 `artifacts/jwc-source/`；支持 `--file` 与显式 `--update-catalog`，详见通识选修节。 |
| `npm run parse` | 用已保存的课表 HTML 重建课表 JSON/页面；存在考试或成绩 HTML 时也重解析它们。不重新登录，也不处理等级考试。 |
| `npm run render:ui` | 以 `timetable.json` 为必需输入，结合已有考试、成绩、等级考试 JSON 重绘本地页面。可选数据文件缺失时生成空页面；JSON 损坏时明确报错。 |
| `npm run export:android` | 从渲染结果生成不含个人数据的 Android 页面模板、公共图片、资源更新 ZIP 与清单。 |
| `npm run check` / `npm run build` | TypeScript 类型检查 / 编译到 `dist/`。编译后可运行 `node --test tests/*.test.mjs`。 |
| `npm run verify:home` | 编译后检查首页十项数据流程，不改 UI、不重复提交密码；使用独立验证产物，见下节。 |

`render:ui` 与 `export:android` 必须串行执行；构建 APK 应在导出之后。

## Android 校园工具约定

- `CampusToolsActivity` 的本地 `campus-tools.html` 只经 `CampusBridge` 查询数据；远程网站在无该桥接的 `CampusBrowserActivity` 打开。
- 本地工具 HTML 使用固定 base/history URL；原生 `onPageFinished` 后调用幂等的 `CampusUI.start()`，再发送 `bootstrap`。原生只接受当前本地页面的桥接请求；`native-start-v2` 标识用于旧资源兼容回退。成绩 HTML 同样指定固定 history URL，供分类更新入口校验。
- `LocalScheduleStore` 在应用私有 `local-schedules.json` 中原子保存 `{version:1,items:[...]}`。条目包含 `id,title,semester,weekday,weeks,weeksText,startTime,endTime,location,notes`；周次 1—30，自由时间 `HH:mm` 且结束晚于开始，地点与备注可为空。提醒和首页使用真实时间，课表按所在教学时段定位卡片。
- 本地日程只在显示个人课表时按学期合并为 `courseCode=local:<id>` 的记录，兼容字段 `startTime/endTime/notes` 可选。教务原始 `timetable.json` 和教师课表不混入本地日程；详情中可进入编辑。
- `rooms.filters/query` 读取学校校区、教学楼和时段；`courses.search/teachers.search` 分别请求 `/kbcx/kbxx_kc_ifr` 与教师选项，教师课表使用 `/kbcx/kbxx_teacher_ifr`，复用个人课表模板并指定学期。
- `library.search/detail/loans` 对应公开检索、馆藏详情与当前借阅；`favorites.list/toggle` 只写本机收藏。借阅不执行续借或预约。公开检索使用独立 Cookie 容器，个人查询复用 WebView Cookie 和校方 SSO。
- 学校查询入口不提交密码；登录过期时给出“前往登录”。异常结果分别提示网络超时、HTTP 状态、跳转或解析步骤，不当作有效空结果。
- `tests/campus-ui.test.mjs` 在无头浏览器使用模拟桥接验证交互，不截屏；`tests/android-campus.test.mjs` 使用编译后的 Kotlin 类验证真实结构解析和日程输入，需先构建 Android。

## 课程评教接口

入口为首页“小工具 → 课程评教”；同一目录内有课程搜索和选课分类目录。目录页面 `toolbox` 允许 `CampusBridge.openTool('courses'|'evaluation'|'enrollment')`，以独立 Activity 打开，返回回到目录。桥接仍在各自本地页面校验，远程网页没有该接口。

选课状态：`bootstrap.enrollment` / `enrollment.status` 返回本机 `{enabled:false,browseEnabled:true,submissionEnabled:false,state:catalog_only,title,message,entryUrl}`。`entryUrl` 只是教务选课中心的元信息；其他 `enrollment.*` 返回 `{ok:false,code:FEATURE_DISABLED,error}`，不执行 HTTP、选退课或后台抢课任务。`enrollment-catalog-ui.ts` 的本地 `buildEnrollmentCatalog/filterEnrollmentCatalog` 共用通识分类表、手机缓存及分类更正；名称/课程号搜索与分类筛选可组合，不依赖教务浏览页，课程号保留前导零，一课多类保留冲突。

仅 Android 的本地 `evaluation` 工具页可以调用以下桥接动作，远程学校网页不暴露此桥接：

- `evaluation.scan`：`{option:0..4,lastOption:0..4,comment:string}` → `{id,courses:[{title,choices,url}],notices}`。选项是零起始顺序；预览绑定会话、10 分钟有效，执行 GET 和 Cookie/SSO 复用，不提交密码或评价。
- `evaluation.submit`：`{id}` → 原生课程/评级确认 → `{items:[{title,state,message,url}],stopped,notices}`。`state` 为 `success/skipped/uncertain/failed/stopped`；确认后消费预览，按课程串行提交并重新验证列表状态。
- `CampusCallbacks.progress(id, json)` 更新 `stage,done,total,title,items`；`CampusBridge.cancelEvaluation()` 停止后续操作，正在返回的请求仍需核实。

提交使用新读取的表单 action、隐藏参数和编码，保留重复字段，过滤 disabled 控件；只向教务评教路径 POST。原生全局锁避免跨页面并发。结果未知记录在发请求前同步落盘，下一次扫描不得重复发送；GET/HTTP 200/保存文字不替代校方完成标记。批次另有脚本确认按钮时提示学校网页核对；没有当前开放批次的真实 POST 验证。

## 通识选修学分统计

- 入口：成绩页的“通识选修学分统计”，由 `src/general-education-ui.ts` 提供计算，`src/general-education-pages.ts` 提供统计、集中分类和可搜索分类表页面，主成绩模板在 `src/score-ui.ts`。统计读取全部成绩，不使用 GPA 勾选列表，也不叠加四六级换算学分。
- 数据：`src/general-education-catalog.ts` 来自用户于 2026-10-29 保存的教务处《通识教育选修课分类汇总（2022版）》正文，共 742 行、741 个课程号；来源 URL、保存日期及原文件 SHA-256 随目录记录。分类表是公共数据，可打入空成绩模板；保存的完整网页位于被忽略的 `artifacts/`，不进入 APK。
- 原表六种标签按原文保留：人文素养类、经济社会类、经济社会类（四史）、艺术审美类、自然科技类、自然技术类。“四史”单列且不再重复加入经济社会类；“自然技术类”不擅自改为其他分类。“00W00184 / 药用植物学”在原表同时属于自然科技类和人文素养类，默认待确认。
- 范围：课程性质/属性属于通识选修、公共选修，或标注“计划外”“辅修”；主修必修和普通专业选修不纳入。计划外精确匹配原表，否则待手动确认，辅修必须手动指定；只计已通过、学分为有效正数的课程。按课程号去重（无号时以名称补充），优先通过记录，再取最近学期；课程代码忽略大小写并保留前导零。
- 分类先按课程号精确匹配，再按规范化课程名称匹配；不做模糊推断。未收录及多分类冲突计入已获总学分，但不归入任何具体类，单列待确认。不同课程号不擅自合并为同一课程，不判定个人培养方案的毕业达标要求。
- 顶部按各类别（含辅修）汇总学分。分类从标题栏进入分栏编辑页，可长按课程或直接拖右侧手柄跨类移动，也支持选择框；拖动只修改草稿，记录一次保存；取消或保存失败不覆盖原数据。手动分类以 `classsche.general-education.categories.v1` 存入本机 WebView localStorage，不改变原成绩。Android 成绩页使用固定的 `https://classsche.local/score-view/` 本地来源，避免资源更新路径变动丢失更正；文件损坏或写入失败保留原分类并明确提示。
- 回归：`tests/general-education.test.mjs` 验证完整分类表、通过/去重规则、冲突保留、页面入口与分类保存；`tests/general-education-drag.test.mjs` 验证真实触摸长按、手柄、边缘滚动、取消、手动待分类和保存重开/刷新，不访问学校、不截屏。

### 公开网页防护与分类表更新

本机实测：直接 HTTP、无界面浏览器、测试框架直接启动的有界面浏览器均出现 HTTP 202→400；普通 Edge 进程、项目独立配置目录及本机 CDP 连接正常得到 202→200，读取 742 行课程记录。程序保留校方脚本，不改 User-Agent、JavaScript 环境或安全检查。

2026-10-29 后续单变量对照使用同一 Edge 130、两个全新配置目录和相同窗口参数：普通启动 `navigator.webdriver=false`，HTTP 202→200，743 个表格行（含表头）；只增加 `--enable-automation` 后 `navigator.webdriver=true`，HTTP 202→400，无正文。两组采集的 User-Agent、Accept、Accept-Language、Client Hints 及 Fetch Metadata 一致，第二次请求均带 `FSSBBIl1UgzbN7NS/T` 两个 Cookie 名称。该开关在本轮对照中构成可复现触发条件；除 webdriver 标识外，还出现自动化提示栏导致的视口高度差，尚不能断言校方脚本只检查 webdriver 一个属性。诊断结果（不含 Cookie 值）在被忽略的 `artifacts/jwc-source/launch-comparison.json`。

```powershell
npm run fetch:general-education
npm run fetch:general-education -- --file "artifacts/通识教育选修课分类汇总（2022版）.htm"
npm run fetch:general-education -- --update-catalog
```

默认只输出有效正文 `notice.html` 与结构化 `catalog.json`。只有 `--update-catalog` 才在完整验证后原子替换 `src/general-education-catalog.ts`；不一致标题、缺失表头、损坏行或防护页均不覆盖旧目录。更新目录后按顺序重新渲染、导出并构建 Android。

`src/public-site-browser.ts` 启动已安装的 Edge（或 Chrome），使用独立目录 `artifacts/jwc-source/standard-edge-profile/`，保持同一浏览器的 Cookie 与本地存储；开发接口只监听本机。它不读取日常浏览器配置或教务账号密码。运行时会出现独立浏览器窗口，成功/失败后关闭；不输出 Cookie 值。完整会话目录属于本机数据，已由 `artifacts/` 的忽略规则覆盖。

`src/general-education-source.ts` 等待正文后直接读取 DOM，不再次 `fetch` 触发新的校验；识别仅含防护脚本的页面，但不因正常正文中也含防护脚本就拒绝解析。防护仍可能再次要求验证，失败时明确给出响应状态并保留本地导入方案。此方法解决桌面公开网页读取，与 Codex 内联浏览器工具权限及 App 内 WebView 是独立路径。

手机端已接入“更新分类表”：`GeneralEducationUpdateActivity` 用普通移动 WebView 加载文章，允许同一文章的 `page.htm` / `pagem.htm`，保留默认 UA、JavaScript、DOM Storage 和 Cookie。`GeneralEducationPageScript` 等待文档解析完和真实表头/数据行，再读取目标表格；`GeneralEducationCatalogParser` 校验每条记录后，`GeneralEducationCatalogStore` 以 AtomicFile 存入私有 `general-education-catalog.json`。缓存结构包括 `schemaVersion:1,title,sourceUrl,imported,cacheSource:mobile,capturedOn,updatedAt,sourceSha256,categories,courses`；hash 对应实际读取的目标表 HTML。

成功后通过 `GeneralEducationPage.updateCatalog` 原位更新统计，保留手动分类；成绩页再次打开优先注入缓存。无缓存使用 APK 内置目录，损坏缓存保留原文件并提示回退。重复触发受原生状态控制，校验/加载等待只计前台 45 秒；手动重试、错误和退出会使陈旧回调失效。导航和暂停会使旧 DOM 读取失效，JavaScript 回调超过 3 秒未返回则重新读取，初始空白文档继续等待。远程页面不暴露 JavascriptInterface，不再次调用 HttpURLConnection 抓正文，不改 TLS 或浏览器环境。2026-10-29 已在 Android 模拟器以普通移动 WebView 实际获取并保存 742 条分类记录。

## 首页无 UI 验证

以下命令用于桌面数据验证，与 Android 校园工具页面的 Kotlin 客户端独立。先准备有效的桌面登录态，然后运行：

```powershell
npm run verify:home -- --state artifacts/storageState.json --semester 2026-2027-1
```

默认输出 `artifacts/home-data/`。后续不传 `--state` 时优先复用这里的 `storage-state.json`，不存在时才使用原来的 `artifacts/storageState.json`。脚本本身不提交密码；教务会话失效时可尝试正常 SSO，统一认证也失效后应先手工完成登录再运行。

可选参数：`--out`、`--semester`、`--campus`（孝陵卫 `01`、江阴 `4y`）、`--week`、`--weekday`、`--start`、`--end`、`--book`。默认查询 2026-2027-1 学期第 10 周周三 1—3 节，图书检索词为“高等数学”；这些是验证样例，不代表会自动判断当前学期或当前日期。

`report.json` 记录每项 `passed` / `failed` / `blocked`、具体失败步骤、数量和密码提交拦截次数；任何一项未通过时退出码为 1。`snapshot.json` 保存四类有效业务数据，必须全部成功才原子写入并读回比对。`room-filters.json`、`empty-rooms.json`、`book-search.json`、`book-holdings.json`、`current-loans.json` 保存各客户端的结构化结果；`manual-courses-smoke.json` 仅用于新增／编辑／删除验证，结束时恢复原内容。原桌面和 App 缓存不会被这些验证文件覆盖。

- `empty-room.ts`：校区、教学楼、学期、周次、星期、起止节次查询；保留每个时段的 `free` / `occupied` / `unknown`，不将未知符号当成空闲。原教学楼接口返回异常时使用校方教室课表的真实备用接口，并保留失败说明。
- `local-courses.ts`：桌面验证用手动课程单独保存，数据版本为 `schemaVersion: 1`，包含 `id`、`source: manual` 和学期；校验名称、星期、周次、节次后原子写入。按学期合并时不修改教务课程。Android 已通过独立的 `LocalScheduleStore.kt` 实现自由时间日程、编辑与提醒接入，不使用该验证文件。
- `library-data.ts`：公开图书查询保留真实表单的 CSRF 字段；支持页码、复本数、馆藏地、索书号及书刊状态。个人借阅优先复用图书馆 Cookie，失效后用现有 IDS 会话经 `http://202.119.83.14:8080/uopac/reader/hwthau.php` 恢复 SSO；不提交读者密码，也不执行续借或预约。8080 为 HTTP 端口，不能强行改为 HTTPS；保留校方原始 service 协议，错误日志去除票据和请求头。
- `common-sites.ts`：常用学校链接及认证类型。验证时使用匿名上下文探测，避免访问 SSO 改变业务 Cookie；仅说明地址服务响应，不把 HTTP 200/202 当作业务数据验证成功。

会话及结果只保存在被忽略的 `artifacts/`，不应提交或打入 APK。新增离线测试：先 `npm run build`，再 `node --test tests/home-data.test.mjs`。实时实测及当前数据样例限制见 [FEATURE-AUDIT.md](FEATURE-AUDIT.md)。

## 登录与业务页面模块

- `src/config.ts` 读取 `.env`。`USERNAME` 和 `PASSWORD` 分别用于统一身份认证用户名及**智慧理工服务门户密码**；`SEMESTER` 可指定考试查询学期。站点 URL 有默认值，见 `.env.example`。
- `src/endpoints.ts` 定义新教务域名、统一认证和 SSO 入口；`src/browser.ts` 复用 `artifacts/storageState.json`。
- `src/login.ts` 填写认证表单并等待成功；`src/authenticated-page.ts` 验证响应码、实际跳转目标和业务页面选择器。`src/raw-page.ts` 用会话读取成绩类页面原始字节并处理编码。
- `src/diagnostics.ts` 为超时、域名解析、HTTP/跳转及解析步骤提供可定位的错误文案。异常业务页不会直接覆盖已有结构化缓存。

## 数据约定

| `artifacts/` 文件 | 产生模块与用途 |
| --- | --- |
| `storageState.json` | Playwright 保存的 Cookie/本地存储，含登录态，不能提交。 |
| `timetable.html`、`timetable.json`、`timetable-view.html` | 原始课表、`TimetableCourse[]`、本地课表页面。 |
| `exam-list.html`、`exam-list.json`、`exam-view.html` | 考试查询结果、`ExamArrangement[]`、考试页面。 |
| `score-list.html`、`score-list.json`、`score-view.html` | 成绩原始页、`ScoreRecord[]`、成绩统计页面。 |
| `level-exam-list.html`、`level-exam-list.json`、`level-exam-view.html` | 等级考试原始页、`LevelExamRecord[]`、等级考试页面。 |
| `home-view.html`、`resources/` | 首页页面及图片变体。 |
| `android-update/manifest.json`、`android-update/classsche-assets.zip` | Android 公共资源更新清单与包。 |

具体字段以 [`src/types.ts`](src/types.ts) 为准。课表 `courseType` 表示课程性质，`courseSequence` 是课程序号；不要互相代用。`credits` 为课程学分。修改这些字段时同步检查 Android 的 `TimetableCourse.kt`、`TimetableParser.kt` 和页面渲染器。

Android 的个人数据保存在应用私有目录，桌面 `artifacts/` 与 Android 运行时缓存不是同一个文件位置。详细构建与安全边界见 [ANDROID.md](ANDROID.md)。

Android 的应用版本检查由 `MainActivity.kt` 负责：有更新和无更新结果都按当前应用版本缓存 24 小时，手动重新获取时绕过缓存；APK 安装包由系统 `DownloadManager` 在后台继续下载，应用恢复时查询并显示进度，也可在弹窗中取消并清理文件。这与 `ResourceUpdateStore.kt` 的公共页面资源热更新是两条独立链路。

Android 登录与后台成绩同步只尝试统一认证，不再处理旧教务登录页的本地验证码；因此构建不再依赖 ML Kit 文本识别。


## 2026-10-30 兼容调整

- `library.search` 的 UI 入参仍为 `{keyword,page}`，返回结构不变；原生/桌面适配器从校方当次表单识别新版 OPAC 参数及 CSRF，不硬编码票据。
- `library.loans` 未登录返回 `LOGIN_REQUIRED`，图书页 `CampusBridge.openLogin()` 打开图书馆官方登录 WebView；其他工具保留统一认证入口。手动登录后刷新借阅，无自动密码提交或续借。
- `GeneralEducationPage.edit()` / `toolbarAction()` 保留接口，逻辑状态仍为 `classification` 以兼容原生保存按钮，但在同一统计页编辑；`state()` 不是新页面标识。移至待分类保存显式空字符串覆盖，不删除该课程原始成绩。
- `TimetableRenderer.toHtml` 新增可选 `includeExams=false`；本人课表调用传 true，教师课表默认 false。考试叠加仅渲染，不改变 `timetable.json` 字段或通知数据源。


### 空闲教室多时段（2026-10-30）

`rooms.query` 新增 `periodRanges:[{startPeriod:1,endPeriod:3},{startPeriod:6,endPeriod:7}]`，共1—6段；仍接受旧 `startPeriod/endPeriod` 单段请求。每段校验学校当前表单可选项，再串行只读查询，返回所有段均空闲的教室交集，按编号去重。空列表、无效段或任一段查询失败均报错，不降级为局部结果。返回 `rooms` 只含id/name，人数不再注入界面；summary列出实际查询的所有节段。前端等待上限180秒，查询在后台线程执行。


### 2026-10-30 展示及入口调整

`TimetableCourse.seatNumber?:string`为可选扩展，旧缓存缺失时隐藏座位行，不影响其他字段。考试场次只保留于原始ExamArrangement，不进入备注。`library.loans`不再暴露于校园工具原生桥接，UI移除我的借阅；公开搜索/详情/收藏接口不变，旧解析函数仅作为离线兼容代码保留。考试默认展开不依赖服务端顺序，按有效时间选择未结束场次。


### 图书馆账户只读接口

新增`library.status`（仅library页面可调用），返回`{cards:[{label,value},…]}`，必须恰好4栏。读取失败/未登录返回错误，不拉取借阅清单或历史，不持久化结果。`openLogin()`在图书馆页启动官方独立登录WebView，返回触发`CampusUI.refreshLibraryStatus()`。官网通过同一WebView Cookie管理器复用会话，不注入原生桥接。`library.loans`仍不对UI开放。


图书馆登录入口最终路由为原生`LibraryLoginActivity`，返回码722刷新四栏；更多官网仍通过private_library浏览器复用会话。四栏值节点存在但内容为空时返回“—”，节点缺失仍报结构错误，不默认为0。原生密码/验证码不存储，不作为Intent参数跨Activity传递。


图书搜索交互：`library.search`参数与回包不变，前端串行执行、合并最新待处理查询、同参复用及忽略过时结果/错误；原生同操作互斥仍保留。失败保留原有书目，允许手动重试，不把网络错误当成空结果。课表配色仅修改呈现映射，不更改课程JSON或原始周次。
