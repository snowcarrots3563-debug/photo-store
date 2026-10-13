# 阶段 1：多 Agent 实施提示词

更新日期：2026-09-23（阶段1实施时使用）。状态：**已执行，留作冻结范围与白名单依据**。阶段1代码/APK已交付但未整体验收；最新设备进度见[开发进度](./开发进度.md)和[验收记录](./android/verification/phase1/验收记录.md)。名称、存储、诚实提示及首次空库/错误补充设计已确认，不重复询问。

## 1. 可直接交给下一次主代理的总任务

请使用多 Agent 协作，执行私人画师图库“阶段1：工程、D基础UI、画师与作品集本地管理”。所有沟通、委派、界面文案、报告使用中文。

先读取以下绝对路径：
- `E:\ChatGPT\photo store\正式开发分阶段计划.md`（技术、接口、精确文件所有权和验收的唯一实施明细）。
- `E:\ChatGPT\photo store\开发文档.md`（v0.7及之后真实版本；尤其2.1.1）。
- `E:\ChatGPT\photo store\开发进度.md`。
- `E:\ChatGPT\photo store\阶段1-多Agent实施提示词.md`。
- `E:\ChatGPT\photo store\ui-demos\方案D-简约大圆角.html`。
- `E:\ChatGPT\photo store\ui-demos\方案D-设计与验证说明.md`。
- `E:\ChatGPT\photo store\ui-demos\参考截图\方案D-2026-09-20\截图说明.md`。
- `E:\ChatGPT\photo store\ui-demos\参考截图\方案D-2026-09-20\截图清单.json`。
- `E:\ChatGPT\photo store\ui-demos\参考截图\方案D-2026-09-20\截图校验.json`。
- 当前目录、父目录及工程内实际适用的AGENTS.md；不能假定阶段0之后未新增。
- 主代理技能 `%USERPROFILE%\.codex\skills\subagent-orchestration\SKILL.md`；UI代理技能 `%USERPROFILE%\.agents\skills\frontend-design\SKILL.md`。

D高于技能默认审美，不重新设计；中文高于技能历史英文委派惯例。使用当前工具真实参数，不照搬旧agent_type/fork_context/档位映射；子代理不指定模型覆盖，继承配置，只记录工具实际可见信息。

开始前核对工程是否已出现、已有修改和版本；如仍非Git仓库，以文件盘点记录，不宣称“工作树干净”，不reset、不覆盖既有工作，不自动git init。工程唯一位置为 `E:\ChatGPT\photo store\android`，包名 `dev.local.artistgallery`。发现与冻结接口冲突时先报告主代理，不让实施代理另选架构。

仅实现：单app模块、Kotlin/Compose/Room、D主题/基础组件/导航/弹窗、主页搜索/加号/添加画师、画师两种视图与空状态、空作品集详情、创建临时名/改名、真实数据库保存及重启恢复、稳定编号、校验/取消、关系计数、中性隔离验收样例。

不实现阶段2–6的导入、共享媒体操作、压缩、文件删除、卡牌/GIF/漫画、导出/备份/恢复；不开发云端、账号、多模块或插件框架。不读私人媒体，不改HTML、基准截图、其他项目或全局工具配置。

## 2. 主代理先执行的关卡与契约

1. 只读复查JDK/SDK、依赖空间、设备。阶段0存在JDK `C:\dev\android-env\jdk\jdk-17.0.20.1+1`、SDK `C:\dev\android-env\Sdk`，PATH未设置；当时adb设备列表为空，模拟器未装。验收目标已由用户于2026-09-23更新并实测为小米Mi 10 / Android17（API37）；shell输入注入受系统权限限制，不借用其他设备历史结论。
2. 用户已发起本提示词执行时，可创建本项目、获取锁定Wrapper/Maven项目依赖；不等于授权安装/升级全局SDK/JDK/模拟器或接受新工具许可。仅进程级指定JDK/项目缓存，不改全局PATH。若依赖获取失败如实报告，不擅自更换版本掩盖。
3. 采用计划6.2精确版本：JDK17、Gradle8.11.1、AGP8.10.1、Kotlin/Compose插件2.2.21、KSP2.2.21-2.0.4、Room2.7.2、BOM2024.11.00、Activity1.9.3、Lifecycle2.8.7、Core1.15.0、Coroutines1.10.2；min26/compile35/target35。不预装NDK或压缩库。
4. 先创建工程壳、数据DTO、Repository和UI契约，再将两份互斥写入白名单派给A/B。契约为计划3.3/8.1；任何签名调整仅主代理串行修改并通知双方。工程壳若暂缺实现尚不能编译，准确记为接口准备，不伪报构建通过。
5. 主代理的先行文件：
- `E:\ChatGPT\photo store\android\.gitignore`
- `E:\ChatGPT\photo store\android\settings.gradle.kts`
- `E:\ChatGPT\photo store\android\build.gradle.kts`
- `E:\ChatGPT\photo store\android\gradle.properties`
- `E:\ChatGPT\photo store\android\local.properties`
- `E:\ChatGPT\photo store\android\gradle\libs.versions.toml`
- `E:\ChatGPT\photo store\android\gradlew`
- `E:\ChatGPT\photo store\android\gradlew.bat`
- `E:\ChatGPT\photo store\android\gradle\wrapper\gradle-wrapper.jar`
- `E:\ChatGPT\photo store\android\gradle\wrapper\gradle-wrapper.properties`
- `E:\ChatGPT\photo store\android\app\build.gradle.kts`
- `E:\ChatGPT\photo store\android\app\src\main\AndroidManifest.xml`
- `E:\ChatGPT\photo store\android\app\src\main\res\values\strings.xml`
- `E:\ChatGPT\photo store\android\app\src\main\res\values\themes.xml`
- `E:\ChatGPT\photo store\android\app\src\main\res\xml\backup_rules.xml`
- `E:\ChatGPT\photo store\android\app\src\main\res\xml\data_extraction_rules.xml`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\MainActivity.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\GalleryApplication.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\model\GalleryModels.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\GalleryRepository.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\GalleryUiContract.kt`
6. A/B结束后，主代理独占集成文件：
- `E:\ChatGPT\photo store\android\app\src\debug\AndroidManifest.xml`
- `E:\ChatGPT\photo store\android\app\src\debug\java\dev\local\artistgallery\fixture\UiFixtureActivity.kt`
- `E:\ChatGPT\photo store\android\app\src\debug\java\dev\local\artistgallery\fixture\FixtureGalleryRepository.kt`
- `E:\ChatGPT\photo store\android\app\src\debug\java\dev\local\artistgallery\fixture\SampleCovers.kt`
- `E:\ChatGPT\photo store\android\app\src\androidTest\java\dev\local\artistgallery\ui\GalleryFlowTest.kt`
- `E:\ChatGPT\photo store\android\app\schemas\dev.local.artistgallery.data.GalleryDatabase\1.json`
- `E:\ChatGPT\photo store\android\verification\phase1\验收记录.md`
- `E:\ChatGPT\photo store\android\verification\phase1\设备参数.json`
- `E:\ChatGPT\photo store\android\verification\phase1\截图映射.json`
7. 接线真实Room仓储，debug独立样例Activity只用内存数据；release没有样例入口/素材。数据库只含阶段1的Artist/WorkCollection两表；作品集数量按关系查询，生产图片数量为真实零。媒体表与资产任务在阶段2迁移，不提前搭框架。
8. 先构建/单测/Lint，运行与截图由主代理排程。只有文件互斥且接口冻结才并行；不得让两个代理操作同一设备或并发修改Gradle/schema产物。

## 3. 委派模板：实施代理 A（数据）

### Objective
实现阶段1真实本地画师/作品集管理，符合计划3.1–3.3的事务、身份、名称、取消与计数规则，不实现媒体文件功能。

### Key Files
可写且仅可写：
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\ArtistEntity.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\CollectionEntity.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\GalleryDao.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\GalleryDatabase.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\RoomGalleryRepository.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\NameRules.kt`
- `E:\ChatGPT\photo store\android\app\src\test\java\dev\local\artistgallery\data\NameRulesTest.kt`
- `E:\ChatGPT\photo store\android\app\src\androidTest\java\dev\local\artistgallery\data\GalleryRepositoryTest.kt`

只读：`E:\ChatGPT\photo store\正式开发分阶段计划.md`、`E:\ChatGPT\photo store\开发文档.md`、`E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\model\GalleryModels.kt`、`E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\data\GalleryRepository.kt`、主代理已冻结构建文件。不能写Gradle、Manifest、UI或文档。

### Step-by-Step Logic
1. 核对Repository的三个observe和三个写入方法、DTO与SaveResult。创建Artist/WorkCollection两表、FK/索引、DAO及RoomDatabase version1。
2. 仓储统一trim名称；画师空白失败、同名允许；同画师手工作品集重名在事务中拦截、不同画师允许。
3. 创建/改名留空时，在同一画师现有名称中从1选最小可用编号；空白改名选号包含当前名称，生成新的临时名。UUID不随名称更改，取消不调用写入。
4. 真实保存完成才返回Saved，错误保留可重试结果；CancellationException继续抛出；禁止破坏性迁移、吞错清库或默认返回成功。
5. 添加必要名称、事务、重开、关系计数测试；数据库仪器测试运行由主代理安排设备，不自行控制。
6. 返回精确文件列表、接口遵循情况、实际已运行检查及未运行检查，不以测试源码存在当通过。

### Functional Constraints
你不是唯一在目录中工作的代理，不得回退或覆盖他人修改。只处理授权文件与任务，完成后报告检查文件、证据、验证结果和未覆盖项。不得读取/解压私人媒体、预建媒体导入/云端、修改契约或新增白名单外文件。发现签名缺项先向主代理报告。所有文字用中文，API标识按冻结名。

### UI/UX Specs
数据层不定义新界面。SaveResult须让D表单准确显示空白/重名/失败；提交失败不关闭草稿、不触发成功Toast；取消无写入。计数不能使用演示常数伪装真实数据。

### Done When
实现上述文件和测试，仓储契约一致，事务/ID/名称/重开语义可验证；向主代理明确JVM及设备测试哪些已运行、结果和缺陷。即使数据通过，也不宣告阶段1或UI验收完成。

## 4. 委派模板：实施代理 B（UI）

### Objective
严格依据D及已确认补充设计实现阶段1UI和ViewModel，接入已冻结仓储接口，禁止重设计或用默认组件外观替换。

### Key Files
可写且仅可写：
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\GalleryApp.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\GalleryViewModel.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\DTheme.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\DComponents.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\DIcons.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\ArtistHomeScreen.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\ArtistDetailScreen.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\CollectionScreen.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\ManageScreen.kt`
- `E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\GalleryDialogs.kt`
- `E:\ChatGPT\photo store\android\app\src\test\java\dev\local\artistgallery\ui\GalleryViewModelTest.kt`

只读：`E:\ChatGPT\photo store\正式开发分阶段计划.md`、`E:\ChatGPT\photo store\开发文档.md`、`E:\ChatGPT\photo store\ui-demos\方案D-简约大圆角.html`、`E:\ChatGPT\photo store\ui-demos\方案D-设计与验证说明.md`、`E:\ChatGPT\photo store\android\app\src\main\java\dev\local\artistgallery\ui\GalleryUiContract.kt`、GalleryModels与GalleryRepository的上述绝对路径。

必须实际查看PNG。主代理委派前，从计划7.2展开相关PNG为 `E:\ChatGPT\photo store\ui-demos\参考截图\方案D-2026-09-20\` 下的具体绝对路径，附在此模板；至少001–010、012–019、021、053及续页、055、122–130。056/057仅用于理解后续/原型专用语义，不实施真实整理/重置。

### Step-by-Step Logic
1. 读取frontend-design技能和D源码，逐张看本次阶段对应PNG，报告真实已查看清单。
2. 实现颜色/字体/圆角/间距/图标/命中区域、导航、D底部弹窗；严格按计划7.1，不做新视觉分支。
3. 实现GalleryApp、ViewModel和四类页面；根组件签名见计划8.1；UI不得直接读数据库或文件。
4. 处理搜索、添加/取消/空白、作品集切换、创建临时名、改名、长名称、系统返回和输入草稿；成功反馈来自真实仓储提交。
5. 未完成入口按阶段N显示诚实D弹窗；空媒体漫画/多选保持原有禁用上下文，管理开关不能假装生效。正式无重置示例，样例由主代理debug Activity注入。
6. 实施计划7.4用户已确认的首次空库和读取/保存失败；执行明确文案，不自行增加状态页。若必须改布局先报告主代理。
7. 添加有意义的ViewModel状态测试；设备截图交主代理统一安排，收到截图差异后在自己的文件内返工。

### Functional Constraints
你不是唯一在目录中工作的代理，不得回退或覆盖他人修改。只处理授权文件与任务，完成后报告检查文件、证据、验证结果和未覆盖项。不改HTML/PNG/Gradle/Manifest/DTO接口/数据代理文件，不新增私人或远程素材，不实现后续媒体功能。不得因为源码看似正确就报告视觉通过。

### UI/UX Specs
D唯一基准。灰白分组、大圆角、蓝色控件、系统字体层级、间距和画师/管理导航不变；图片完整容纳。正式文案及已确认偏离原型之处按计划7.3登记。系统栏/IME正确留出区域，CSS像素不得当物理像素。007/010/017的错误/Toast时机按截图前提复现，不人为制造成功。用户确认的临时名规则优先016的旧数字10。

### Done When
全部授权UI文件实现并接冻结接口；提交状态测试证据、基准映射、未覆盖与需主代理设备检查的事项。发现UI不符合，修改后重拍复核；没有实际安卓截图时必须标“UI待验证”，不能以编译通过放行。

## 5. 主代理集成、只读审查与修复

主代理接线后派独立代理C，使用以下六项结构；发送时附计划8.1中49个精确文件绝对路径和实际生成的验收记录／截图路径，不给目录无限写授权。

### Objective
只读检查阶段1需求、数据正确性、D对照证据、样例隔离、文件范围与版本，不实现新功能。

### Key Files
只读上述实际已创建的49个文件（由主代理按计划8.1完整展开）；只读 `E:\ChatGPT\photo store\android\verification\phase1\验收记录.md`、设备参数/截图映射及对应实际截图；只读D HTML、开发文档2.1.1及基准PNG。不要把尚未存在的交付物当证据。

### Step-by-Step Logic
1. 检查已确认需求有无遗漏/缩减，阶段2–6有无越界实现。
2. 核查UUID、名称校验、事务、计数、重开、取消与失败；检查本地备份排除和release不含样例。
3. 核查版本与测试日志确实对应当前源文件，不把实现者测试直接当独立复验。
4. 对每页/弹窗/错误/成功/窄宽尺寸检查实际应用截图与基准、内容/滚动/系统栏；没有截图记缺失。
5. 仅返回按严重度排序的问题，包含路径/行号、复现、影响、修复建议、证据与未覆盖项。

### Functional Constraints
你不是唯一在目录中工作的代理，不得回退或覆盖他人修改。只处理授权文件与任务，完成后报告检查文件、证据、验证结果和未覆盖项。只读不写、不控制共享设备、不擅自改基准，不泛化重构建议，不替用户确认后续产品规则。

### UI/UX Specs
严格开发文档2.1.1和计划7节；截图不符合即不通过，不接受“大致像”或构建代替。新增/正式文案差异须有确认记录，动态验证不以静态图代替。

### Done When
只返回可执行问题或明确无发现，同时列出未检查/受阻范围；无设备的情况下不能给完整运行或视觉通过结论。

主代理将问题回派原文件所有者修复，每次仍保留六项委派结构和精确写入范围；C复查修复后，主代理做最终受影响测试和UI复核。不要让C直接改文件，也不要在问题未解决时新增下一阶段任务。

## 6. 验证、交付与停止

按计划8.2运行：
- `gradlew.bat --version`，再 `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug`。
- 有设备后 `:app:connectedDebugAndroidTest`、debug安装运行、正常Room数据的强制停止/重启验证。
- 独立样例Activity逐状态截图，对照390主体、360/430补充；宽窗口1280检查居中上限，不等于电脑客户端。记录设备、API、逻辑内容尺寸、密度、字体缩放、系统栏与滚动位置。
- 每项记录基准文件、应用截图、差异、修复、重拍和复核。不符合直接返工，禁止修改基准迁就实现。
- 无设备时继续不依赖设备的授权工作；设备运行/仪器/UI仍受阻，不能以源码或构建冒充完成。安装模拟器或变更全局工具需另行明确授权。
- 本轮阶段1交付实际源文件清单、APK绝对路径、构建/数据/运行/截图记录、代理可见配置、未通过项；进度仅勾真实完成项。
- 主代理可增量更新 `E:\ChatGPT\photo store\开发进度.md` 和确需同步的 `E:\ChatGPT\photo store\开发文档.md`；不改旧HTML/截图。新增范围或接口先更新计划再委派。
- 阶段1完成或因必要条件受阻时停止，不自动启动阶段2、导入私人媒体或解压任何私人包。
