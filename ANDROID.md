# Android 说明

Android 工程位于 `android/`，桌面抓取工程位于 `src/`。应用提供原生首页、登录、课表/考试/成绩/等级考试页面、本地缓存、课程与考试通知、成绩后台同步，以及版本和资源更新检查。

## 登录与本地数据

- 账号密码和微信扫码在认证后共用 `LoginNavigationPolicy.kt` 与 `MainActivity.continueLoginNavigation`：门户正常落点包括 `/index.html`、`/default/index.html` 和 `/default/index`，随后沿用当前 WebView/Cookie 访问教务 `indexsso.jsp`，再从教务主页进入课表。仅用户提交登录或启动扫码后继续，每个阶段去重，避免 `onPageCommitVisible` 与 `onPageFinished` 重复请求；不能把门户落点直接视为课表同步成功，仍须验证 `#kbtable` 后才更新缓存。`AUTH_FLOW` 日志记录不含票据的中转地址及后续步骤。
- 登录入口由 `UniversityEndpoints.kt` 统一定义。应用使用 WebView 完成统一身份认证，再通过教务 SSO 建立教务域名的会话。登录页有提交登录、加载登录页和微信扫码登录入口；认证页从首次加载就使用电脑端 User-Agent，避免点击扫码后再切换 UA 重载整页。`IdentityQrLoginController.kt` 和 `IdentityQrPageScript.kt` 直接通过 canvas 读取当前网页已加载的二维码，在原生弹窗展示，不再次 HTTP 下载图片，也不等待其他后台请求队列。表单可用后即可操作，不必等轮播图等资源加载结束。学校要求图形验证码时，登录页显示当前网页已加载的图片，用户手动填写或刷新；不引入 OCR 库或模型。页面使用与主页相同的背景和卡片宽度、蓝色控件，以及黄色的校方限制提示。
- 登录页使用**智慧理工服务门户密码**。`CredentialStore.kt` 用 Android Keystore 中的不可导出密钥进行 AES-GCM 加密；旧版保存在偏好设置中的明文密码在首次读取时迁移并删除。后台同步需要在未打开界面时解密，因此密钥不要求每次生物识别。
- 每次进入登录界面只重新加载认证表单，保留现有 Cookie，不自动登录或抓取课表。`UniversityEndpoints.LOGIN_FORM` 直接访问统一认证并携带 `renew=true`；`MainActivity.authLoginRequested` 仅由提交登录或扫码按钮开启，在此之前拦截跨站 SSO 跳转并禁止业务页面捕获。二维码弹窗关闭后也回到准备表单的状态。已有登录或同步正在执行时复用当前操作，避免重复触发；课表验证码交接先恢复后台 Cookie，再准备表单，填写并提交后才继续认证和同步。
- 微信扫码弹窗提供“保存到相册”，由 `QrImageGalleryStore.kt` 把当前显示的二维码保存为 PNG 到 `Pictures/周三课表`，保留扫码弹窗和认证轮询。二维码未加载或已过期时不能保存；保存期间禁用重复点击，失败清理未完成图片。Android 10 及以上使用 MediaStore，不申请相册读取权限；Android 8/9 仅在点击保存时申请写入权限，拒绝后提示原因。
- 二维码过期时清除旧图片并提示点击“重新获取”。该按钮调用学校页面上刷新按钮同用的 `refresh()`，恢复二维码开关、清理旧轮询再请求新码；刷新期间禁止重复申请和保存旧码，仅页面缺少脚本/表单时才回退重载一次认证页。准备密码表单时暂停扫码轮询，点击扫码后才恢复。二维码申请、图片加载和扫码状态检查分别显示超时、HTTP 状态码、网络或解析错误，诊断中不输出含二维码票据的 URL。
- 校方扫码状态接口返回文本：`0` 等待扫码、`2` 已扫码待确认、`1` 微信已确认、`3` 二维码失效。状态检查由原生前台 Handler 每秒驱动同会话网页 `isUsed()`，最多一个请求在途，不依赖隐藏 WebView 的定时器；切去微信暂停前台检查，返回后立即替换陈旧的状态请求，不申请新二维码或重新提交密码。收到 `1` 后只提交一次当前真实二维码表单，保留其隐藏字段、CAS service 及 WebView Cookie，跳过重复 ID 的隐藏模板。弹窗显示确认、认证、教务会话和课表加载进度；到达门户/教务主页后立即继续教务入口，不等待门户资源全部加载。单个认证步骤 20 秒、总流程 60 秒无进展会明确提示超时（用户切后台的时间不计入），认证返回登录页时读取校方错误；`QR_AUTH` 日志记录状态码和脱敏后的跳转步骤。
- 登录页加载、提交和扫码期间会禁用重复操作；首页“更新数据”（保留原刷新图标）优先复用已有教务会话，必要时使用保存的凭据在后台完成统一认证、所选学期课表抓取及相关缓存同步。通常不打开登录页；校方要求图形验证码时自动跳转到登录页，填写后提交登录继续更新。同步期间显示“更新中…”，重复点击只提示当前操作；失败时保留原课表缓存并显示具体错误。
- `HeadlessLoginClient.kt` 和 `HeadlessScoreSyncManager.kt` 处理纯 HTTP 登录及成绩同步，并遵守 Cookie 的域名/路径范围；课表静默更新复用该登录器，并用返回的教务 Cookie 拉取考试、成绩及等级考试。后台登录先查询学校验证码要求；需要验证码或收到图形动态码错误时通过 `CaptchaRequiredException` 把认证地址和 Cookie 交给登录 WebView，不反复提交。WebView 重新加载认证页后获取当前会话的验证码，手动验证成功后继续抓取课表。
- `LoginCookieStore.kt` 将后台响应中域范围有效的 `Set-Cookie`（包括过期删除）写回同一 WebView Cookie 存储，等待异步写入完成后才继续，在本轮结束时 `flush()` 落盘。`LoginCookiePolicy.kt` 保留原站点和大学父域 `njust.edu.cn`，忽略校方语言偏好 Cookie 的无效 `Domain=edu.cn`，避免 WebView 拒收它后中断整个同步。重新加载登录表单不删除已有会话；先验证教务 Cookie，失效后尝试 CAS Cookie 恢复 SSO，仍出现认证表单才提交一次密码。网络、HTTP 和解析错误不触发密码回退。认证段共用锁，最多等待 15 秒，单次认证网络流程最多 60 秒。`HEADLESS_SESSION` 日志注明复用结果，不打印 Cookie 或票据。
- 首页手动“更新数据”使用独立执行队列，避免排在图片预载或资源下载后。结束后显示完成、部分失败或具体错误弹窗，并解除忙碌状态；需要验证码时仍交给登录页手动填写。
- 自动成绩同步由 `HeadlessScoreSyncManager` 中的进程级 `ScoreSyncTriggerPolicy` 控制：进程启动后首次打开主界面额外尝试一次，不受后台定时间隔限制；进程仍存活时切回前台、重新进入或重建主界面都不重复。首次触发因设置、认证暂停或已有同步而跳过时也不在切回前台后补试。进程被结束后重新打开可再次触发。后台闹钟继续按设置间隔执行（默认 2h，可选 1h、2h、5h、12h、24h）；`ScoreSyncSettings` 持久保存最近尝试时间，`ScoreSyncTiming` 计算剩余时间，启动同步也从本次尝试起重新计算下次闹钟。关闭同步、仅 Wi-Fi 和认证失败暂停同样约束启动同步；账号冻结、密码被拒或需要验证码后暂停自动认证，手动登录或手动更新课表成功后解除暂停。
- 个人课表、考试和成绩缓存写入应用私有目录，升级 APK 时保留。应用关闭系统备份；不要把密码、Cookie、票据或用户数据提交到仓库。
- “检查更新”将有更新和无更新结果都缓存 24 小时；有更新时连同版本、发布页、下载地址和简介一起保存，再次点击直接显示结果。弹窗中的“重新获取更新”会绕过缓存并重新请求发布页。新版本号仍用于设置页红点。
- 安装包使用系统 `DownloadManager` 下载；切到后台或主界面重建后任务仍由系统继续，返回应用时按持久化的任务 ID 恢复下载弹窗和进度。下载弹窗的叉叉可撤销下载任务并删除未完成的安装包；下载完成后通过 `FileProvider` 打开系统安装器。
- 应用统一设置 Material 主色和强调色为蓝色，覆盖设置开关、弹窗按钮和下载进度控件。
- 通知设置、后台成绩设置、课表学期、运行日志和本地成绩编辑使用与登录页一致的蓝色主题、浅色背景、满宽卡片和圆角。
- 考试提醒提前量使用与其他单选设置一致的白色弹窗，选择后立即保存并更新提醒计划。

## 首页校园工具

新增页面沿用蓝色顶栏、浅色背景、满宽白色圆角卡片和中文标题字体。正文 13px、课程名 14–15px、按钮 13–14px，与现有成绩页层级接近；所有 MaterialToolbar 的标题/副标题一起居中。工具页以 `campus-typography-v3` 回退旧热更新模板，日程编辑、图书子页和教师搜索切换原生标题。首页“添加课表”改为“添加日程”；首页“小工具”合并课程搜索、课程评教和“选课 / 抢课”三个可进入的页面；选课页面现阶段仅提供本地课程分类目录，子页返回先回到“小工具”。“图书馆”合并搜索、当前借阅及本机收藏。

- 日程由 `LocalScheduleStore.kt` 在私有目录独立原子存储，保留当前学期、星期与周次；起止时间自由选择，可跨课间，地点选填，并有备注。保存后直接查看课表，点击卡片详情可编辑或删除。首页近期安排与已有提醒功能使用真实时间，不上传服务器，不覆盖教务数据。
- 空闲教室支持学期、校区、教学楼、时段、周次/星期和近四天快捷选择；使用学校表单的实际选项。查询失败保留上次结果并显示具体错误。
- 课程搜索支持课程关键词及“课程名；教师名”，也可切到教师搜索。教师课表复用个人课表 UI、独立指定学期，不写入个人缓存，也不叠加本地日程。
- 图书搜索支持分页、馆藏位置和可借状态、收藏；借阅查询沿用统一认证会话，学校明确返回空记录才显示无借阅。续借、预约与服务器数据上传下载不在本次范围内。
- `CampusDataClient.kt` 通过已有 Cookie / SSO 查询，禁止自动提交密码，过期时提示前往登录；公开图书检索单独保存匿名会话。远程常用网站在无 JavaScript 原生桥接的独立 WebView 打开。
- `CampusToolsActivity.kt` / `CampusBrowserActivity.kt` 使用 `activity_campus_tools.xml`；本地页面源文件为 `src/campus-tools-ui.ts`，导出时生成 `campus-tools.html`。旧热更新课表模板缺少日程支持时回退本次 APK 自带模板。
- 工具页使用固定本地 history URL，在 `onPageFinished` 后通过 `CampusUI.start()` 初始化桥接；旧工具模板缺少 `native-start-v2` 时回退 APK 模板。避免 Android 将 history URL 显示为 `about:blank`，导致初始化请求被原生校验忽略。新页面统一通过 `SecondaryPageToolbar.kt` 采用现有登录/设置页的 44dp 返回按钮、4dp 上下边距和状态栏处理。

## 选课目录与课程分类

“小工具 → 选课 / 抢课”可点击进入课程分类目录，来自与成绩页相同的 2022 版通识分类表（742 行 / 741 个课程号）。`src/enrollment-catalog-ui.ts` 按课程号去重，保留一课多类及原表分类标签；支持名称/课程号搜索、六类及“分类待核对”筛选、组合筛选和 30 门一页。课程卡片中的“选课”仍禁用，页面明确区分分类目录和本学期可选课程，不显示虚构学分、课时或余量。

`CourseEnrollmentFeature.kt` 将浏览状态与提交状态分开：`browseEnabled=true`，发布与教务适配两个提交开关仍为 `false`。教务入口 `UniversityEndpoints.COURSE_ENROLLMENT`（`/njlgdx/xk/AccessToXk`）仅保留元信息；没有调用它。原生目录及深链允许进入浏览页，所有选课业务请求仍返回 `FEATURE_DISABLED`；仅 `enrollment.status` 读取本机状态。当前没有选课/退课 POST、余量轮询、重试或后台抢课任务。

进入页面时 `GeneralEducationCatalogStore.inject` 注入成绩页同一私有分类缓存，缺失或损坏时回退 APK 内置目录并保留原缓存。课程分类更正只读成绩页的同源 `localStorage` 项 `classsche.general-education.categories.v1`，同时显示原表类别，不写回成绩。浏览、搜索、筛选和翻页均为本地操作，不登录或请求教务列表。旧资源缺少 `enrollment-catalog-v1` 时回退 APK 模板。真实选课接口待开放批次后再适配。

验证：`tests/enrollment-catalog.test.mjs` 覆盖原表去重、前导零/全角课程号、组合筛选、分类冲突、本机更正、分页和文本转义。模拟器 `RuntimeChecks` 的 `phase=enrollment` 已验证真实目录跳转、同一手机分类缓存注入、搜索筛选及绕过前端后的 `FEATURE_DISABLED` 拦截，未请求学校选课系统。

## 课程评教

首页“小工具 → 课程评教”进入评教页。普通题默认第 1 项、最后一题默认第 2 项，可改为第 1—5 项并填写共用评语；“读取待评课程”仅做 GET，读取批次、未评课程和逐题实际选项，展示后才能点击“一键评教并提交”。改变设置立即废弃旧预览。原生确认弹窗再次列出课程及选项顺序；取消不发送评价。预览有效期 10 分钟，并绑定当前教务会话。

`TeachingEvaluationClient.kt` 仅用已有教务 Cookie 或 CAS Cookie 恢复 SSO，不自动提交密码。评教表单沿用原始隐藏参数和编码，提交前重新读取课程状态及表单，核对预览指纹和选项；已经完成则跳过。请求仅允许 HTTPS `bkjw.njust.edu.cn/njlgdx/xspj/`，POST 采用固定长度流且不重试；遇到 307/308、超时、登录失效或校方状态未确认时停止。HTTP 200 或“保存成功”文字本身不会当作完成。

`TeachingEvaluationEngine.kt` 串行提交，各门之后重新 GET 列表确认“已完成/已评教”等状态。全局互斥与原生在途状态避免重复触发；发送前将课程标记存入私有 `evaluation_uncertain_<账号摘要>` 偏好，确认完成才移除。进程退出或网络中断留下的标记，下次读取时排除自动提交，防止重复；用户需先在学校网页核对，App 不会自行清除此标记后重发。停止操作只停止后续课程，当前可能已经发出的请求仍需等待结果核对。

题目数量、选项不足、必填项或验证码等异常会明确提示，并提供学校网页入口。若校方仍显示批次确认按钮，当前版本提示到学校网页核对批次，未猜测执行未取得的校方 JavaScript 确认逻辑。现阶段尚无当前开放批次的真实提交验收，表单兼容性依据参考项目 DOM 结构和离线样例；不应宣称所有批次已在真实账号提交验证。

功能参考用户提供的 [njust_evaluation](https://gitee.com/forever-ezio/njust_evaluation) 的流程与页面字段，未打包或执行其无许可证声明的脚本；本仓库独立实现原生 HTTP、预览、状态验证和防重发。无新依赖，也不下载远程执行代码。`tests/evaluation.test.mjs`、`tests/android/EvaluationCheck.java` 使用模拟服务验证表单、逐门 POST、会话变化、重复点击、超时、取消及记录保留；设备端 `RuntimeChecks` 的 `phase=evaluation` 使用内存模拟表单，不访问学校服务器。

2026-10-29 已通过 47 项仓库回归，并在 `emulator-5554` 验证真实 WebView → 原生桥接 → 确认弹窗 → 模拟提交 → 列表状态核对：取消发送 0 次 POST，确认发送 1 次模拟 POST，无真实学校评价提交。APK 版本号保持现有值，未自动递增或发布 Release。

## 成绩页通识选修统计

成绩页新增蓝色“通识选修学分统计”按钮，进入独立内容页，顶部显示总学分，并按原表各类别及“辅修”分别显示学分，下面查看课程明细；待分类学分作为辅助提示。标题栏“修改分类”直接在当前统计页进入编辑状态，不另开页面；长按课程块 450ms 或直接拖右侧三条杠跨栏移动，支持边缘自动滚动与空分类投放；无背景三条杠拖动分类，删除按钮仅将课程移到待分类，只有待分类课程提供下拉框。一次保存全部修改；返回先回上一级。统计纳入通识/公共选修和“计划外”“辅修”候选，只累计已通过课程，辅修必须手动指定，按课程号去重，不影响现有 GPA/四六级统计；分类更正仅保存在本机固定来源的 localStorage，保存后重开应用、更新成绩和分类表都保留；未保存返回需确认，取消拖动或写入失败保留旧记录。成绩数据不修改。

公共分类表 `src/general-education-catalog.ts` 由 2026-10-29 保存的校方 2022 版网页提取，含 742 行、741 个课程号。保持原表六种标签及“四史”独立分类；重复课程号的冲突不自动选择，未收录和冲突课程单列待确认。分类名称可本机更正。计算见 `src/general-education-ui.ts`，统计页内编辑和分类目录见 `src/general-education-pages.ts`；旧热更新模板缺少 `general-credit-pages-v3` 时使用本次 APK 自带的新模板，确保安装后按钮可见。

### 手机更新分类表

在“成绩查询 → 学分统计 → 课程分类表”的标题栏点击“更新分类表”。分类表支持课程名称/课程号搜索、类别筛选及分页。`GeneralEducationUpdateActivity.kt` 使用 App 内普通移动 WebView 打开校方网页，保留默认手机 UA、JavaScript、DOM Storage 和 Cookie，等待真实课程表出现后由 `GeneralEducationPageScript.kt` 读取目标表 DOM；不转用原生 HTTP 客户端、不添加远程页面原生桥接。只允许同一教务处文章的桌面/手机版地址，HTTPS 校验保持默认。

`GeneralEducationCatalogParser.kt` 验证标题、表头、每行字段和课程号，完整保留重复冲突。`GeneralEducationCatalogStore.kt` 在应用私有 `general-education-catalog.json` 中原子保存有效目录；网络、HTTP、跳转、解析或保存失败均保留原表。成功返回时更新当前统计页面，保留本机分类更正；再次打开成绩页优先注入手机缓存，无缓存时使用 APK 内置目录，缓存损坏时保留文件并显示提示。

更新中禁止重复启动；界面显示加载、校方校验、保存或具体错误，提供手动“重新获取”。45 秒前台无结果即报错，切后台暂停前台超时计时，返回后继续读取；用户退出或失败会使旧回调失效，并在提交前检查取消状态。页面读取不会在 HTML 尚未解析完整时保存部分表格。不新增运行时库。

分类读取将初始 `about:blank` 当作等待，并在网页导航、后台暂停或 JavaScript 回调超时后重置读取状态，防止旧文档回调丢失后卡住。成绩页注入所用正则同时转义左右花括号，兼容 Android ICU；桌面 JVM 的宽松正则行为不能代替设备验证。

2026-10-29 已在指定的 `emulator-5554` 验证：成绩和统计页打开、五个工具页实际桥接初始化、顶栏高度与原设置页一致、跨课间日程保存、教室查询、教师课表、图书及馆藏、借阅和首页数据更新。移动 WebView 已真实获取并原子保存 742 条分类记录；不提交密码、不截屏。

`app/src/androidTest/java/com/classsche/mobile/RuntimeChecks.kt` 直接使用 Android Instrumentation，无额外测试库，不打入主 APK。在 `android/` 构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，安装两份对应 APK 到指定模拟器后运行：

```powershell
adb -s emulator-5554 shell am instrument -w -e phase local com.classsche.mobile.test/com.classsche.mobile.RuntimeChecks
```

`creditDrag` 使用模拟课程和隔离的本机分类键，验证实际 Android WebView 触摸长按、手柄跨栏拖动及保存重开；2026-10-29 已在 `emulator-5554` 通过，不改动用户分类记录。`presentation` 只验证本地新页面字号、标题/副标题对齐、集中分类与逐级原生标题栏返回，测试期间禁用启动同步并在结束时恢复设置；不查询学校、不截屏。2026-10-29 已在 `emulator-5554` 通过该阶段验证。`local` 检查平台 ICU、实际桥接、成绩入口及顶栏；`interactions` 验证页面按钮到原生请求和本地日程保存；`queries` 验证真实查询与更新数据；`catalog` 验证移动分类表获取。后几项访问学校并更新模拟器私有缓存；测试日程在验证后删除。

## Android 资源与构建

`npm run export:android` 把**空数据页面模板**、公共首页图片和 `cache-meta.json` 写入 `android/app/src/main/assets/`，并在 `artifacts/android-update/` 生成资源包和清单。导出时会移除 `assets/` 中旧的个人课表 HTML/JSON、考试与成绩 JSON；`artifacts/` 中抓取的个人数据不会被打入 APK。有关资源更新的代码见 `src/export-android.ts`、`ResourceUpdateStore.kt` 和 `MainActivity.kt`。

如果修改网页模板，按顺序执行：

```bash
npm run render:ui
npm run export:android
```

随后在 `android/` 目录，使用 JDK 17 和 Android SDK 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

调试 APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。`render:ui`、`export:android` 和 APK 构建必须串行；否则应用可能包含上一次生成的页面。

本机双平台发布工具集中在被 Git 忽略的 `release-tools/`，不随仓库分发。根目录的 `npm run release` 读取 `release-tools/release-notes.md`，自动增加 `versionName` 和 `versionCode` 后串行生成资源、构建并校验 APK；APK 和热更新附件上传到 GitHub/Gitee。脚本仅提交版本配置，推送当前分支和新标签，失败用 `--resume` 继续同一版本。配置和说明位于本机的 `release-tools/README.md`，输出及续传记录位于 `release-tools/output/`；默认沿用当前本机调试签名。

## 主要源码

| 位置 | 作用 |
| --- | --- |
| `MainActivity.kt`、`activity_main.xml` | 主界面、WebView、缓存更新和页面导航。 |
| `UniversityEndpoints.kt`、`HeadlessLoginClient.kt` | 站点入口和纯 HTTP 认证。 |
| `CredentialStore.kt`、`FailureDetails.kt` | 本地密码加密、迁移和错误提示分类。 |
| `Timetable*`、`Exam*` | 课表/考试解析、渲染、学期与提醒。 |
| `HeadlessScoreSync*`、`ScoreSyncSettings*` | 成绩后台同步、调度和设置。 |
| `NotificationSettingsActivity.kt`、`CourseNotification*`、`ExamNotification*` | 上课与考试通知。 |
| `AppDebugLog.kt`、`LogViewerActivity.kt` | 运行日志。 |

以上 Kotlin 文件在 `android/app/src/main/java/com/classsche/mobile/`；完整索引见 [FILE-INDEX.md](FILE-INDEX.md)。

## 排错

- 登录 WebView 主页面失败会显示连接超时、WebView 错误码或 HTTP 状态码；统一认证返回 HTTP 401 等登录失败页面时优先显示校方表单内的错误原文。提交表单后找到输入框并点击按钮只代表请求已发起，不代表账号密码验证成功。异常跳转会显示目标地址。登录页出现但业务表格缺失时，不应把登录页写入缓存。
- 课表更新后的考试、成绩、等级考试同步结果分别显示；解析或网络失败可在“运行日志”查看具体步骤和错误。原有缓存应保留。
- 网页已更新但 APK 还是旧版：检查 `render:ui → export:android → assembleDebug` 的执行顺序，并确认安装的是新 APK。
- ADB `unauthorized` 需要在手机上确认 USB 调试授权；`INSTALL_FAILED_ABORTED` 通常表示设备端取消了安装确认。

桌面端的 Playwright 登录态文件不能直接当作 Android Cookie 使用。站点迁移与已验证页面见 [LOGIN-MIGRATION.md](LOGIN-MIGRATION.md)。


### 2026-10-30 校园页面调整

- 学分编辑留在当前页面，标题保持“学分统计”，保存后回到统计状态；显示分类总学分，课程卡片不再显示第三行分类依据。黄色说明整块兼作课程分类表入口，课程搜索无圆角且横向贴边。
- 新建/编辑日程周次可全选 1—20 周，仍可单独取消某周。只改变选择，不自动保存。
- 本人课表通过 `TimetableRenderer.toHtml(includeExams=true)` 叠加考试缓存；`ExamTimetableOverlay` 按已知学期周一映射日期/星期/周次，去重并显示真实起止时间、教室和座位。未知校历或学期外考试不猜日期；教师课表默认不叠加，不把考试写回课程缓存，也不改变已有考试提醒。
- 公开 OPAC 已升级为 v5.6：搜索根据当前表单识别 `q/t/m` 或旧 `strText/strSearchType`，保留该次会话 CSRF。Android 书目编号先 URL 解码再统一编码，防止详情双重编码。
- 校方旧 `reader/hwthau.php` 已实测返回 404。借阅复用 WebView 的图书馆会话；未登录时，“前往登录”打开官方 `reader/login.php`，登录后返回借阅页点刷新。不会将学校统一认证成功冒充图书馆已登录，也不会将连接失败显示成无记录。
- 图书搜索置于页面最顶部，搜索按钮无背景；常用网站标题栏可复制当前实际导航地址，外部网页（常用网站、登录页、分类更新页及远程业务页）开启双指缩放，不显示浮动缩放按钮。远程页不增加原生数据桥接。
- 新设备回归阶段：`refinement` 检查周次全选、考试卡片详情、真实双指缩放、复制链接及分类拖动/删除；`libraryNew` 请求校方公开搜索/馆藏，并区分借阅登录缺失。测试课程、分类键与截屏均使用模拟数据；公开馆藏截屏不含账号资料。


### 2026-10-30 布局及多时段查询补充

学分统计和分类目录搜索框保留页面12px左右边距，仍无圆角。图书搜索移至三栏切换下方。`rooms.query` 支持 `periodRanges`（1至6段），逐段只读查询并取教室编号交集；空选前后端均拦截，一段失败则整次失败，不展示不完整交集；保留旧单段参数兼容。切换校区移除不支持的时段，退出页面后中止后续分段查询。UI支持非连续大节多选，不包含中间未选时段。首四教学楼兼容汉字、阿拉伯数字及Unicode/ASCII罗马数字，筛选保留“全部”首项，其后为一至四教学楼；结果去掉人数。

借阅：本轮再次核对真实OPAC登录页，没有发现统一认证链接；校方2025版图书馆使用指南仍要求登录“我的图书馆”。学校统一认证Cookie不能据此当成图书馆登录。保留现有图书馆会话复用及官方登录页，不尝试向其他地址重放密码。来源：https://lib.njust.edu.cn/_upload/article/files/19/fa/3deb5cb443a9a6a568f6a8125aa3/4582142b-6bf8-4a06-bb30-73c05c9ca496.pdf 第17页。


### 2026-10-30 详情格式与首页整理

- `LocalScheduleStore.formatWeeks`将已排序周次按连续区间格式化，如`1-3,5,7-8`；新保存数据、旧日程列表/编辑和课表详情均兼容。周次数组保留，不丢失原选择。
- `TimetableCourse`新增可选`seatNumber`字段，考试覆盖层从原始考试缓存填充；场次`40001`保留在原始考试信息中，但不再冒充备注。课表详情在地点后显示独立座位行；日程/考试标为“周数”，普通课程保留“课程周数”。空备注隐藏。
- 书目卡片独立使用紧凑样式，不影响课程搜索和其他工具的行间距。首页/网页首页统一叫“图书搜索”。真实手机日志借阅仅返回未登录，无借阅数据；按用户兜底要求移除借阅标签与原生桥接公开动作，保留历史解析器用于旧格式回归，不清除用户缓存/收藏。
- 首页`applyToolbarLayout`不再套用个人中心的56dp最小高度及额外10dp底部内边距；个人中心保持原设置。Git将该旧配置追溯到057a3a3（2026-04-30“增加课前手机通知功能 优化了个人中心UI”）。状态栏安全区保留，不把内容顶进系统栏。
- 考试安排`exam-default-expansion-v2`按实际起止时间选最近未结束考试，进行中优先；已结束及时间未知默认收起，全部结束无默认展开，手动开关保持。Android遇旧模板回退APK新模板；课表详情兼容标记更新为`exam-timetable-v2`，校园工具为`campus-typography-v6`。


### 图书馆独立登录与四栏状态（替代本轮移除账户入口的方案）

`library.status`只读`reader/redr_info.php`，仅解析`.infobox-container .infobox`中的4个状态块；不提取profile、图表或借阅历史，结构不完整则明确报错，不填造数据。账号页只提供独立登录、刷新和打开官网，更多操作交给原站。登录使用官方WebView表单，验证码由用户填写；成功后返回并重新核验状态，不能仅凭网页跳转宣称登录成功。

登录/官网页通过`private_library`启用LOAD_NO_CACHE、禁用DOM存储、表单保存和自动填充；原生reader请求禁用缓存。不写入状态JSON/HTML、个人资料或真实截图；账密保存策略见下方最新加密存储说明。仅保留WebView会话Cookie供后续原站访问复用；不导出Cookie。测试使用.env的`LIB_USERNAME/LIB_PASSWORD`，不能使用学校`USERNAME/PASSWORD`；.env不进入APK或Git。临时浏览器真实登录验证已完成，测试上下文关闭后清理登录态；仅记录结构结论，不记录数值和个人页面。


### 原生图书馆登录框

`LibraryLoginActivity.kt`/`activity_library_login.xml`替代直接网页输入：原生EditText及类型选择，隐藏且无原生桥接的官方WebView仅负责既有CSRF、验证码和密码编码。验证码通过同源Canvas从已加载图片复制到本地ImageView，RAM内解码；不识别验证码、不上传、不截图、不落盘。换一张重新获取官网表单，提交用requestSubmit（兼容submit按钮点击）触发官网原编码一次。字段禁用状态保存/自动填充、页面禁用缓存/DOM存储，窗口FLAG_SECURE防止登录截图；销毁时清空密码/验证码，成功保留供官网复用的WebView会话Cookie；本轮新增授权后，还通过独立Keystore密钥加密保存图书馆账密和登录类型。

已通过真实模拟器四栏API和官网已登录状态验证。官网荐购图书一栏的数值可能是空span；保留“—”表示未提供，不编造0。模拟登录框图片/路由测试使用明确的TEST图片，不提交真实账号；表单编码、单次提交、异常目标拦截使用模拟凭据。用户真实验证码仅由本人输入。


原生登录入口首次先请求reader/redr_info.php核验已有会话，仅未登录时由原站引导登录表单；避免在已有会话时直接打开login.php使原站会话失效。测试账密通过测试APK的ADB本机内存通道填入，不放入adb命令参数、Intent或文件；该通道不在正式应用APK中。


页面版本：校园工具`campus-typography-v8`、课表`exam-timetable-v4`、学分统计`general-credit-pages-v7`；旧资源包缺少标记时回退APK模板。图书搜索前端在单个原生请求进行中保留最新查询，避免触发原生`inFlight`拒绝；过期回调不覆盖新结果。图书馆密码提示使用原生TextInputLayout的placeholder，不写入密码字段。


### 图书馆成功登录后的本地账密保存（2026-10-30，替代此前仅内存策略）
`LibraryCredentialStore.kt`使用独立Android Keystore AES-256-GCM密钥，将账号、密码、登录类型整条加密并通过AtomicFile写入noBackupFilesDir，隔离于教务CredentialStore。只有原生提交后官网出现认证标记才保存；失败/取消/仅复用既有会话不保存，保存失败明确提示。重开原生框自动填入保存的账密和类型，不自动提交或代答验证码。数据不进入网页模板、日志、Git或Android备份。Instrumentation阶段`libraryCredentials`只用隔离test命名空间和虚构账密验证加密、失败不覆盖、成功保存、重开及已有会话不覆盖；收尾删除测试文件和密钥。


ReleaseVersion.kt支持alpha.N/beta.N/rc.N，更新页面保留完整版本后缀并按数值序号比较，正式版本高于同核心预览版本。预览APK同样递增versionCode，使用现有签名。


### 原生学校邮箱
入口：CampusToolsActivity仅从可信本地小工具页打开未导出的SchoolMailActivity。邮件/授权码不走JS桥接。SchoolMailAccountStore复用独立namespace的Keystore AES-GCM存储；与图书馆和教务凭据隔离。凭据配置仅在IMAP/SMTP认证通过后保存。
SchoolMailClient使用Eclipse Angus Mail 2.0.5（官方Android兼容说明：https://eclipse-ee4j.github.io/angus-mail/Android），Activation 2.0.3/API 2.1.4。默认IMAPS993、SMTPS465，系统证书及主机名校验，不放宽TLS；固定腾讯服务主机，不支持任意邮件服务器。连接15秒、读写25秒超时，后台执行，不自动重发SMTP。
列表每页30条，仅预取envelope/flags/UID；用UID和UIDVALIDITY校验与分页，不跨邮件夹复用编号，READ_ONLY和peek不改已读。正文最多512KB，MIME深度/数量有界，附件不取内容，HTML只转文字。首版不实现附件发送、完整HTML、删除、标已读或离线正文缓存。
SchoolMailOutbox只持久化唯一草稿编号、账号指纹、Message-ID、时间和发送状态，不存正文或授权码。accepted=SMTP受理；unknown/partial不能当发送失败自动重试；进程中断的sending转unknown。成功后核对/追加已发送副本，副本失败不再发SMTP。
SchoolMailPolling默认关闭，JobScheduler持久任务74120、联网约束和15/30/60分钟周期；系统不保证准点。通知74121默认不显示邮件标题/发件人。新UID基线按账号与UIDVALIDITY隔离；撤销配置/停用轮询不继续后续通知。轮询结果以通用错误描述存入本机设置，不记录原始协议或邮件内容。
设备测试schoolMail使用内存假Store/Transport，覆盖读写逻辑但不冒充真实协议送达；schoolMailTls只握手及欢迎语；schoolMailReal的.env内存接入仅存在测试APK；schoolMailSendTest仅用户单独授权时执行，通过本机内存通道接收本人指定的单个收件人并先查唯一记录。未配置有效授权码时不能宣称真实收发通过。
