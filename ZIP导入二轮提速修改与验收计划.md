# ZIP 导入二轮提速修改与验收计划

编写日期：2026-10-13。用途：供后续承接 Agent 核对现状、实施修改和验收。

当前状态（2026-10-14）：第1、2、3、6项顺序实施、逐项验证及新独立代理审查完成；0.2.1／12最终313 JVM、两APK、Lint及Android回归源码编译通过。APK与完整日志已核对，准备按授权提交分支及唯一标签；发布结果见远端v0.2.1和本轮交付记录。历史2026-10-13交接为只读状态，不据其回退实现。手机未连接尚未安装，自动测试暂停，真实提速幅度未验证。

## 1. 请求、基线与实施顺序

用户反馈 ZIP 图片导入慢，先要求只读调研，再要求说明各项收益和后果，最后要求编写供其他 Agent 修改的文档。本文保留这次调研结论；承接 Agent 的代码实施范围以其会话中的用户指令及当前 AGENTS.md 为准。

- 项目根目录：`E:\ChatGPT\photo store`；Android 工程：`android/`。
- 文档编写时 Git 分支为 `main`，HEAD 为 `75a0ce83b4429d171256aaf6b6125bf5fbcd5324`，工作区已有其他需求的修改及未跟踪文件，不能当作干净基线。
- `updates.json` 已登记 `0.2.0`／11 候选；排序、空作品集清理、分享和视频正在另一轮整合中，具体状态见[对应实施计划](./排序清理分享与视频实施计划.md)。这不代表本轮 ZIP 优化已经纳入该版本，也不证明该候选已发布或装机。
- 根开发文档中的部分摘要早于当前实施记录；开工时重新读取最新文档、源码、Git 状态和版本资产，不依据旧摘要回退代码。
- 0.1.7 已移除逐张整批快照、建立条目查找索引，并将同次新 ZIP 的完整文件哈希读取从三遍降为两遍，见[历史交付记录](./android/verification/zip-import-speed-20261011/交付记录.md)。不要重复实现这批已完成的改动。

建议顺序：准备与基线记录 → 第 1 项排序索引 → 第 2 项流式哈希 → 第 3 项无用订阅 → 第 6 项准备阶段节流 → 整合检查与独立审查。第 4、5 项作为后续候选，需要计时支持和恢复设计；第 7 项不作为独立优化实施。

没有手机计时结果时，可以完成源码中已确认重复工作的修改与非设备检查，但交付必须保留“真实提速幅度未验证”。手机自动测试继续暂停，不自动操作私人图库。

## 2. 收益估算及可能后果

统一按“总耗时减少比例”表示收益：减少 20% 表示原来 20 分钟变成 16 分钟。估算关系为：`整体耗时减少比例 ≈ 被优化环节占总耗时的比例 × 该环节耗时减少比例`。

下面所有耗时占比都是说明用的假设，不是本项目实测值。只有第 1 项有合成内存数据库的查询工作量验证；不能将指令数量直接换算为手机耗时，也不能把各项示例百分比相加。

| 项目 | 可以量化的变化 | 有明确条件的整体收益示例 | 后果与风险 |
| --- | --- | --- | --- |
| 1. 排序索引 | 现有表结构、1000 条同组引用的内存查询，SQLite 虚拟机指令约 6016 → 20 | 若排序查询占总耗时 10%，其时间降低 90%，整体约减少 9%；若只占 1%，收益最多也接近 1% | 多一个索引的空间及写入维护开销；需要显式数据库迁移，迁移失败会影响升级 |
| 2. 写流时计算 SHA-256 | 新 ZIP 图片的完整文件回读从两遍降为一遍，SHA-256 仍计算两次 | 若两遍纯回读开销占总耗时 20%且成本接近，整体约减少 10%；热缓存下可能很小 | 保留最终落盘及恢复核验时不改变原图内容；删除这些核验会削弱损坏检测。公共写入方法还被备份恢复使用 |
| 3. 减少无用列表查询 | 暂停导入期间不可见页面的查询，实际减少量取决于订阅情况 | 若额外查询、排序和 UI 更新占总耗时 20%，减少其中 90%，整体约减少 18%；相关开销很少时收益接近 0 | 暂停的缓存可能短暂过期，重新可见时必须补刷新；可见列表节流会改变刷新体验，不能直接扩大范围 |
| 4. 批量最终入库事务 | 保留前三次状态写入、每 20 张合并最终提交，1000 张约 4000 → 3050 次事务，减少约 24% | 若事务固定开销占总耗时 40%，且每次固定开销接近，整体约减少 10%；多数 SQL 和文件处理仍在 | 取消可能等待一批收尾；中断后需核对一批。恢复实现错误可能遗漏项目、重复引用或错误报成功 |
| 5. 两阶段流水线 | 下一张解压与上一张校验／提交有限重叠 | 理想稳态下两阶段耗时比 9∶1时，处理阶段减少约 10%；1∶1时最多约 50%。真实收益可能为 0或负值，整包准备时间仍需另计 | 额外暂存文件、CPU／I/O 争用、发热及耗电；顺序、去重、取消及共享读取器状态更复杂 |
| 6. 复制阶段节流 | 空间检查由每 128 KiB 改为每 16 MiB时，调用次数可少约 99.2% | 若检查／进度更新占总耗时 5%，减少其中 90%，整体约减少 4.5%；只影响预览前准备阶段 | 进度及空间不足提示稍晚出现，需要空间余量、最终更新和写入失败处理 |
| 7. 仅设置 ZipFile.setBufferSize | 当前 getInputStream 路径没有传递该设置 | 对当前路径预计约 0%，不值得单独实施 | 真正改内部流时需要重新验证密码、分卷及 CRC，不属于单行参数调整 |

第 1 项验证使用项目 Room v4 的 CollectionMedia 表结构、合成引用和桌面 SQLite 3.45.3 的内存数据库：100 条约 618 → 22 条指令；1000 条约 6016 → 20 条指令。候选索引为 `(collectionId, manualOrder, assetId)`；查询结果不变。这不是 Android Room、手机存储或整包导入计时。

## 3. 必须保留的行为

- 原图片、GIF、视频的字节及来源文件保留；不重编码、降低分辨率或将 GIF 转静态图。PDF 沿用现有白底、长边 2560 像素、逐页 PNG 及页面独立身份规则，不以降低画质换速度。
- 一份 MediaAsset 原文件、多份 CollectionMedia 引用；SHA-256／字节数查询、逐字节去重及同组不重复引用规则保留。
- 保留 `flush`、`fd.sync()`、提交前最终原图回读核验；恢复／重试重新核验磁盘，不复用旧进程内的核验结果。
- 保留取消提交边界、逐项结果、失败重试、中断恢复及完成通知语义；关闭面板、离开页面不取消任务，不承诺进程被终止后继续运行。
- 保留根散图按包名归组、深层目录按相对路径建平级作品集、同名另建带序号名称；失败目录不建空作品集，仅视频的有效目录可导入。TXT 仍跳过。
- 密码、分卷、路径穿越拒绝、空间／大小／条目／时间上限及小于 5120 字节跳过规则不因提速放宽；不递归解开嵌套包。
- 保留当前用户显式排序、manualOrder、封面、返回位置和已有备份格式。公共接口变化必须核对相册、文件、文件夹、RAR／7z及完整备份恢复的调用方。
- 不用 `synchronous=OFF`、删除同步落盘或取消校验等方式替代优化，不新增全局并发配置、通用任务框架或无关重构。

## 4. 现状定位与逐项修改要求

以下源码路径以 `android/app/src/main/java/dev/local/artistgallery/` 为根；测试位于对应 `src/test`、`src/androidTest`。行号可能变化，以函数名定位。以下是候选文件范围，承接 Agent 开工前须核对并冻结必要清单，不表示可以修改整个目录。

### 准备：记录基线与分阶段耗时

1. 读取 AGENTS.md、本文、三份开发记录及当前正在整合的实施计划，记录分支、提交、版本、数据库版本、现有差异和实际依赖。保护用户及其他 Agent 的修改，不 reset／clean／覆盖旧件。
2. 区分 `copyVolume` 整包复制、目录准备／PDF 渲染、条目解压写入、同步落盘、哈希回读、媒体边界识别、逐字节去重、数据库提交和 UI 查询。
3. 优先使用现有测试辅助、查询计数或追踪；必要计时只在调试／验收路径聚合记录，不逐个缓冲块写日志，不增加用户配置或输出密码、私人文件名／路径。
4. 普通 `.zip` 使用 ZipArchiveReader；`.zip.001` 等入口可能走 NativeArchiveReader。记录实际适配器，不把 Zip4j 的结论自动套到全部分卷／RAR／7z。

验证：获得可复核的基线清单与局部计数；没有获准进行设备测试时，设备总耗时写“未测”，不填推测数据。

### 第 1 项：覆盖排序查询的索引

现状：`data/GalleryDao.kt::nextManualOrder` 每次查询同组 `MAX(manualOrder)+1`；`data/CollectionMediaEntity.kt` 目前只有 collectionId／assetId 单列索引及复合主键，不能直接从排序字段取最大值。`data/RoomGalleryRepository.kt::commitArchiveImport` 每张新引用调用该查询。

修改要求：

- 优先评估增加 `(collectionId, manualOrder, assetId)` 覆盖索引，保持查询结果及所有存量 manualOrder 不变。不另建内存排序计数器或修改默认排序。
- 根据开工时真实数据库版本添加显式迁移；编写时是 v4，不预占未来版本。同步 `data/GalleryDatabase.kt`、迁移注册处 `GalleryApplication.kt` 和正式 schema JSON。
- 不清库、不删除旧字段／索引、不使用 destructive migration；索引变化不改变备份清单字段。

验证：空组、单图、排序值有空隙／重复、同资产跨组、已有大量引用后追加；迁移后 ID／引用／封面／排序／原文件保留。使用 EXPLAIN QUERY PLAN 与合成大组查询趋势确认候选索引有效，不跨 SQLite 版本硬编码虚拟机指令数；设备迁移测试尚未运行时明确记录。

### 第 2 项：新 ZIP 写入时计算哈希

现状：`data/importing/MediaFileStore.kt::writeToStaging` 写入并同步后调用 inspectStaging；`data/importing/MediaImportCoordinator.kt::finishFinalized` 再 inspectOriginal。相册复制已有流式哈希；新 ZIP 没有必要重新解压或再次读取来源 ZIP来取哈希。

修改要求：

- 在新 ZIP 解压写入的同一字节流上计算 SHA-256及实际字节数，减少随后暂存文件的完整回读；流必须读到正常结束，仍由现有读取器完成 CRC／加密完整性检查。
- 保留同步落盘、媒体边界识别、最终原图回读校验和逐字节去重；提交前最终核验不通过时，不产生 READY资产及成功引用。
- STAGED／FINALIZED恢复、失败重试、进程重启必须重新读盘；只有刚完成的同次新写入可携带流式结果，不把它伪装为跨进程磁盘核验缓存。
- `data/backup/BackupRestorer.kt` 也调用 writeToStaging，且 prepared／confirm 各有核验。默认保持备份恢复的现有磁盘核验契约；如调整公共方法，先列出所有调用方影响并保留相应保证，补备份恢复回归，不能仅靠 ZIP单测关闭此项。
- 使用最小必要改动，不为测试新建通用文件存储框架，不增加用户可配置“是否校验”的选项。

验证：原字节一致、不同格式、正确／错误密码、CRC损坏、短写／写入失败、相同内容复用、同长度内容篡改、STAGED／FINALIZED恢复、取消到达写入与提交边界；核对新图只做一次最终完整文件回读、哈希仍计算两次。具体测试优先复用 ArchiveImportIntegrationTest及现有恢复测试。

### 第 3 项：暂停 ZIP导入期间不可见列表的查询

现状：`ui/GalleryViewModel.kt::observeArtists` 常驻；`observeMediaRoute` 可能在导入面板覆盖当前页面时继续加载列表并排序。Room因相关表变化重新执行查询，已有 distinctUntilChanged只能减少重复通知。

候选范围：`ui/GalleryViewModel.kt`、`ui/GalleryApp.kt`，以及确有必要的状态／注入连接处；先冻结实际清单。不要直接在整个 RoomGalleryRepository中全局关闭 Flow。

修改要求：

- 首轮只减少 ZIP实际导入期间不可见／被遮挡列表的 SQL与排序工作；保持导入进度、结果、通知及可见页面可用。
- 区分“任务正在运行”与“面板是否可见”。用户关面板、导航到媒体页或再次打开选择器时，恢复相应查询，不展示长期过期缓存。
- 任务完成、失败、取消、密码／补卷中断及页面重新可见时补刷新；保留搜索、错误重试、返回位置、显式排序和多选有效 ID。
- 已有首页缓存不能回退为每次普通导航都重新加载。导入实际改变数据时正确失效、补查询；无变更时保留已加载内容。
- 不以在 Flow结果之后加 debounce／sample代替查询控制；如需可见页面定时刷新，先单独说明体验变化和具体条件，不把示例刷新间隔当作已确认产品规则。

验证：合成千项导入的 DAO查询次数／列表排序次数前后对比；导入面板开关、返回首页／画师／作品集、失败／取消／恢复后最新计数及列表、通知结果打开、搜索和多选。确保订阅恢复无重复任务、不错误取消导入，也不把真实结果覆盖为旧缓存。

### 第 6 项：整包复制阶段节流

现状：`data/archive/ArchiveImportCoordinator.kt::copyVolume` 每读取 128 KiB都调用 StatFs并更新进度；该环节发生在导入预览之前。

修改要求：

- 用固定内部字节／时间阈值减少重复检查与进度更新，可评估空间检查每 8–16 MiB、进度约每 200 ms；这些是实现候选，不新增用户设置。
- 开始、结束、失败及取消时提供最终状态；保留空间余量、完整复制长度核对、同步落盘和 `.copying` 暂存清理。
- 空间检查间隔必须与保留余量匹配；其他程序同时写入导致空间不足时，写入异常仍正确停止和清理。不得为测试填满真实手机存储。
- 不修改 ZIP正文解压、PDF转换质量或密码／分卷逻辑。

验证：合成来源的检查／进度回调次数显著减少；最后字节数与进度准确；主动注入短写／IOException、取消及未知来源长度，核对失败不报成功、暂存按原规则清理。

## 5. 后续候选：第 4、5项及压缩流参数

### 第 4 项：批量最终提交

只有前述优化后的计时仍显示最终事务固定开销明显时，再冻结具体实现。当前每张新图约有 COPYING、STAGED、FINALIZED状态写入及最终资产／引用事务；文档中的事务数量模型仅适用于完整的新图成功路径，重复／失败／跳过路径另计。

候选为小批量短事务，仅合并已完整核验文件的最终资产、引用及终态写入，不把解压、文件同步或哈希塞进长数据库事务。保留逐项持久记录、幂等和同组顺序；不得将完成状态提前记在事务提交之前。

批次大小 20只用于估算，不是冻结参数。实施前必须说明：取消是否等一批、崩溃后如何识别每个最终文件、事务失败后哪些项目重试、部分失败及空间不足如何处理、如何保留单项结果及通知。恢复／逐字节去重／失败不建空组回归通过前，不交付该改动。

### 第 5 项：两阶段流水线

只有实测显示解压与后续校验／提交可以重叠，且增加并发不会造成存储争用时再做。候选为一个读取／解压生产者、一个校验／提交消费者和很小的有界队列，队列只传暂存文件元数据，不存整批图片字节。

不能并发使用当前共享 ArchiveReader：ZipArchiveReader具有 remaining、密码和目录缓存等可变状态，PdfArchiveReader也维护缓存映射。提交／去重保持串行，按既有顺序分配 manualOrder；整体可用空间预算、取消传播、失败清理和恢复不得退化。

需分别比较串行与流水线的总耗时、内存、同时暂存字节、CPU／I/O负载及稳定性。收益未超过测量波动、变慢或恢复复杂度不合理时，不纳入交付。

### 第 7 项：缓冲参数及 PDF

Zip4j 2.11.6的 getInputStream经 UnzipUtil创建默认配置 ZipInputStream；仅调用 ZipFile.setBufferSize不会改变该路径内部缓冲。不得以这个单行改动声称提速。直接更换流实现或压缩库需要独立格式／密码／分卷验证，首轮不做。

PDF在预览前逐页转 PNG，需单列准备／渲染耗时。本轮没有已验证的等质量 PDF加速方案，不给其编造收益，不降分辨率或改有损编码。

## 6. 验收、证据与发布

### 验收样例和记录

- 使用自行生成或明确获准的中性 ZIP：100／1000／5000条；同一作品集大量图片与分散小组分别测量；大图、小图、重复内容分别统计；普通图包与含 PDF图包分开。
- 覆盖 JPEG／PNG／GIF／视频、普通／密码／分卷、混合跳过项、损坏、错密码、缺卷、空间异常、取消及恢复。小于 5120字节的图会被跳过，计时样例必须记录实际可导入数量及字节数。
- 记录设备／系统、实际适配器、应用与数据库版本、源码提交或快照、样例 SHA-256、条目／组数、压缩／展开大小、原图库规模、可用空间、各阶段耗时、成功／复用／跳过／失败数量及查询计数。
- 设备测试恢复授权后，同设备、同样例、相同初始图库及缓存条件进行至少三次对比，报告中位数和波动。新图导入与重复导入必须使用可比较的初始状态，不能第一遍新增、第二遍全部复用后声称加速。
- 当前手机自动测试暂停：可先完成非设备合成检查与 Android回归源码编译，真机运行、真实数 GiB提速及交互验收仍待进行。

### 整合关卡

1. 每项先建立能检出原问题的回归或计数证据，再修改；源码及调用方检查通过后进入下一项。共享 MediaFileStore、DAO、VM及接口串行修改，不允许多个实现 Agent同时写同一文件。
2. 独立审查重点检查迁移与存量保护、流式结果／磁盘核验差异、备份恢复调用方、查询暂停恢复、取消及幂等。审查不是手机性能验收。
3. 在 `android/` 使用项目现有 JDK／缓存／检查脚本运行 JVM、Debug及交付变体 APK、Lint和 Android回归源码编译。当前交付变体为 phase2Test，任务示例为 `:app:testDebugUnitTest :app:assembleDebug :app:assemblePhase2Test :app:lintDebug :app:compilePhase2TestAndroidTestKotlin`；开工时复核实际任务，不为执行示例升级依赖或复制工程。
4. 检查失败停止发布，保留失败及最终证据；不拿旧报告当作新验证，不用 Android源码编译代替运行结果。
5. 完整版本完成后遵循 AGENTS.md的既有自动发布授权；版本／versionCode唯一来源仍是 updates.json，同步完整分类日志、开发文档、进度、阶段计划及本文。本文不预定下一应用版本，不占用／移动已有标签。
6. 只暂存本轮审核范围，保留其他工作；排除凭证、签名、私人媒体／数据库、缓存和构建输出。推送后核对远端分支及附注标签，禁止 force push；未经另外要求，不公开上传 APK。单独文档交接不构成应用发版。

建议将实施证据放在 `android/verification/zip-import-speed-round2-20261013/`，不提交私人数据及构建输出。报告至少列出：每项修改、实际文件范围、回归结果、构建／Lint／Android源码编译结果、设备是否运行、前后耗时与波动、未验证事项和发布结果。

## 7. 承接 Agent提示词

```text
请在 E:\ChatGPT\photo store 工作，先读 AGENTS.md、ZIP导入二轮提速修改与验收计划.md、开发文档.md、开发进度.md、正式开发分阶段计划.md及正在整合的排序清理分享与视频实施计划.md。

首先只读核对当前源码、Git差异、版本及数据库结构，说明基线和必要文件范围，保护已有修改。以本会话用户给出的实施授权为准；本轮文档生成不表示已有代码修改、手机实测或发布。

获准按本文实施时，依次完成排序索引、仅新ZIP路径流式哈希、不可见列表查询控制及准备阶段节流；每项建立回归／计数证据、整合验证后再进入下一项。共享文件串行修改。公共MediaFileStore必须检查完整备份恢复调用方；保留fsync、最终磁盘校验、逐字节去重、取消／恢复、原图字节、GIF／PDF质量和分组排序规则。

批量事务与流水线先取得瓶颈证据并冻结恢复设计，不能因理论收益自动实现；仅setBufferSize不作为优化。所有示例百分比均为假设，未测项目必须写未测。

手机自动测试仍暂停，不自动操作私人图库。先完成非设备检查及Android回归源码编译，保留真机性能待验收状态。同步实施MD与三份开发记录；完整版本交付按AGENTS执行独立审查、构建验证、版本／日志核对及已授权的Git发布，检查失败停止。
```

## 8. 交接时执行状态（2026-10-13，实施进度见第10节）

| 项目 | 状态 | 本次证据 |
| --- | --- | --- |
| 只读调研与收益／后果说明 | 完成 | 当前源码、0.1.7历史交付及合成内存查询计划 |
| 交接文档与开发记录同步 | 本次交付 | 本文及开发文档／开发进度／正式阶段计划 |
| 第1、2、3、6项代码修改 | 待实施 | 本次未改源码 |
| 第4、5项候选冻结 | 待计时与设计 | 无实测收益结论 |
| 本轮构建／JVM／Lint／Android源码编译 | 未运行 | 文档交接不触发 APK构建 |
| 本轮真机性能／设备验收 | 未运行 | 手机自动测试暂停 |
| 本轮应用发版／Git发布 | 未执行 | 不改应用版本，不创建标签 |

## 9. 技术依据

- 当前[排序查询](./android/app/src/main/java/dev/local/artistgallery/data/GalleryDao.kt)、[引用索引](./android/app/src/main/java/dev/local/artistgallery/data/CollectionMediaEntity.kt)及[归档入库](./android/app/src/main/java/dev/local/artistgallery/data/RoomGalleryRepository.kt)用于定位第1、4项。
- 当前[媒体文件存储](./android/app/src/main/java/dev/local/artistgallery/data/importing/MediaFileStore.kt)、[媒体导入协调器](./android/app/src/main/java/dev/local/artistgallery/data/importing/MediaImportCoordinator.kt)及[完整备份恢复](./android/app/src/main/java/dev/local/artistgallery/data/backup/BackupRestorer.kt)用于核对第2项的共享契约。
- 当前[ViewModel](./android/app/src/main/java/dev/local/artistgallery/ui/GalleryViewModel.kt)及[压缩导入协调器](./android/app/src/main/java/dev/local/artistgallery/data/archive/ArchiveImportCoordinator.kt)用于定位订阅、复制和串行处理；Room官方说明表变化触发[可观察查询重新执行](https://developer.android.com/training/data-storage/room/async-queries)，distinctUntilChanged只控制结果通知。
- Android官方介绍[批量插入与SQLite性能](https://developer.android.com/topic/performance/sqlite-performance-best-practices#batch-multiple-insertions-in-a-single-transaction)；SQLite官方[EXPLAIN QUERY PLAN](https://www.sqlite.org/eqp.html)用于分析索引。两者不提供本项目手机加速百分比。
- Zip4j 2.11.6的[ZipFile](https://github.com/srikanth-lingala/zip4j/blob/v2.11.6/src/main/java/net/lingala/zip4j/ZipFile.java)、[UnzipUtil](https://github.com/srikanth-lingala/zip4j/blob/v2.11.6/src/main/java/net/lingala/zip4j/util/UnzipUtil.java)及[ZipInputStream](https://github.com/srikanth-lingala/zip4j/blob/v2.11.6/src/main/java/net/lingala/zip4j/io/inputstream/ZipInputStream.java)用于核对缓冲设置传递路径。官方资料于2026-10-13调研核对；源码／版本变化后重新核验。

## 10. 2026-10-14实施跟踪

- 基线main `f32740d410370dac17d84267cb2774cb4f1cf8c8`，0.2.0／11；172项构建输入及既有文档差异已记录，证据目录`android/verification/zip-import-speed-round2-20261014/`。
- 第1项：只读核对真实schema、索引、迁移注册及回归范围；`zip2_index_research`已分发，尚未改生产代码。第2、3、6项待前项通过。
- 第4、5项缺少计时及恢复设计，保持候选；第7项不单独实施。手机／真实大包耗时未测，自动设备测试暂停。

第1项范围冻结（2026-10-14）：CollectionMediaEntity.kt增加非唯一三列覆盖索引，GalleryDatabase.kt显式v4→v5仅CREATE INDEX，GalleryApplication.kt完整迁移链注册，KSP生成5.json；新增GalleryIndexMigrationTest.kt。DAO、仓储、字段、manualOrder及schema1–4保持。先有实际v4 SQL候选证据100/1000/5000场景，所有表行/共享引用/封面不变；1000指令6018→22仅桌面趋势。验证旧版1–4到5/存量与新增数据、空组/间隙/重复/负值/跨组及索引可用，设备迁移未运行。

第1项首次42秒检查：并行两个变体KSP时phase2Test读空JSON失败，Debug随后生成有效5.json；疑似共用导出路径首次生成竞态，失败日志已保留。确认schema1–4哈希不变、5仅新增三列索引，未删除schema或缓存，改为--max-workers=1串行编译重检（源码不改），尚未进入第2项。主代理schema对比脚本首次访问可选views键失败，改用空列表默认后通过。

第1项已通过（2026-10-14）：3生产＋1新Android测试＋生成5.json，新增4 Android源码；297 JVM零失败/错误/跳过、Android源码编译3分1秒通过，173输入一致。仅新增覆盖索引，schema1–4不变；实际生产SQL桌面v1–4→5及100/1000/5000索引查询/所有旧行/封面/引用保护通过。未运行Android迁移或手机计时，Lint本项未重跑（最终统一）。开始第2项只读核对writeToStaging所有调用方及流式哈希可携带边界，未改第2项生产。

第2项范围冻结（2026-10-14）：四生产MediaFileStore.kt/MediaImportCoordinator.kt/PdfArchiveReader.kt/ArchiveImportCoordinator.kt；默认旧writeToStaging落盘inspect不变，内部流式选项仅QUEUED且真实ZipArchiveReader非PDF虚拟页。importArchiveItem仅fresh写入分支惰性调用资格，恢复/有原件失败重试不碰源/PDF；局部写流计数/SHA覆盖int与offset写，writer/flush/fsync成功后返回，最后inspectOriginal、媒体边界、逐字节去重、取消边界保留。五测试文件共新增1 JVM/8 Android：Zip CRC、FileStore混合写/失败/默认磁盘变更、Import恢复篡改/取消、PDF路由、Backup同长度篡改；已有相关回归保留。非ZIP/Native/FOLDER/PDF及FAILED重解压不启用，完整哈希回读2→1为调用图结论而非手机测量。代理获准定点实施，未新增用户设置或测试注入。

第2项通过（2026-10-14）：4生产/5测试，新增1 JVM/8 Android；298 JVM零失败/错误/跳过及Android源码编译3分17秒通过，174输入一致。普通新ZIP原条目完整哈希文件回读2→1且SHA仍两次仅为源码调用证据；备份默认读盘、恢复/重试、最终磁盘hash和逐字节比较保留。实际媒体格式/密码/IO/恢复设备矩阵未运行，Lint留最终。开始第3项只读核对不可见ZIP实际导入时的列表订阅/恢复边界，未修改该项生产。

第3项范围冻结（2026-10-14）：仅GalleryViewModel.kt/GalleryApp.kt两生产；新GalleryArchiveQueryPauseTest.kt JVM与GalleryArchiveQueryRoomTest.kt Android。条件inline visible&&format ZIP&&step Importing；DisposableEffect按(vm,Boolean)切换与同步dispose恢复，不按整份progress状态重开。VM paused+首页恢复意图两个内部bool，cancel现artists/detail/media三job且不改缓存/loading/selection/scroll；观察入口暂停守卫，暂停中明确Retry延期，已失败首页不自动重试；false恢复应恢复首页和当前route，不导航。Home catch/collect补ensureActive；Collection加载标志以保留的collection及匹配父artistDetail初始化，保持删除空组安全返回。普通导航首页不新增订阅，导入历史/任务/进度/通知保持。1000模拟invalidation逐个调度查询及排序物化计数，实际Room SELECT计数测试仅编译；无生产统计注入或一次性predicate封装。

第3项回归先已新增15 JVM＋3 Android源码；代理为避免Kotlin库内部实现耦合省略可选toArray排序物化计数，没有生产统计注入。千次逐个模拟invalidation检验源查询baseline+1000/paused+0/resume+1及缓存identity；取消后无媒体流发射故不进排序为代码机制结论，不宣称独立实测排序次数或耗时。实际Room SELECT计数3个测试仅源代码编译，尚未运行。生产两文件接线中。


第3项两生产接线已完成并进入主代理串行检查；176输入捕获，15 JVM/3 Android回归。主代理核对Boolean-keyed effect与三查询job取消，不触碰导入job；新出现的0.2.2规划文档属于同期其他工作，保留、不纳入本轮。


第3项通过（2026-10-14）：2生产/2新测试，新增15 JVM/3 Android；313 JVM零失败/错误/跳过及Android源码编译3分5秒通过，176输入一致。千次模拟无暂停+1000查询、暂停+0、恢复+1及缓存/选中/搜索/顺序/迟到结果/空组返回回归通过；实际Room计数和Compose面板运行未做，不能当作手机SQL/速度验证。Lint留最终。开始第6项只读核对copyVolume字节/时间限频及原同步/异常/取消边界。


第6项范围冻结（2026-10-14）：仅ArchiveImportCoordinator.kt的copyVolume及SystemClock导入；新增ArchiveCopyVolumeTest.kt 6个中性Android回归。8MiB刷新实际空间并逐块从缓存扣实际字节，缓存不足下一块+16MiB时立即重查，保持原小文件可用空间条件；不新加24MiB门槛或结束余量拒绝。进度单调时钟200ms、起始0及finally最后写入MB，cancelling时不覆盖取消提示；128KiB、逐块取消、fd.sync、已知长度核对/未知EOF、rename与.copying清理保持。所有格式共有pick预览前复制都会受益，FOLDER独立路径不变，正文解压/PDF/密码不动。合成64MiB空间检查512→8为源码政策模型，非实际StatFs计时；Android六例已知/未知长/短源/IO/取消/rename仅编译，真实写盘/sync及缺空间故障待设备授权，禁止填满手机或测试注入生产。


第6项1生产/1新Android测试（6例）已完成，主代理核对177输入相较第3项只变2项，copyVolume以外仅SystemClock导入改变；初始/周期/final进度均守卫cancelling，原16MiB小文件空间条件/最终校验保留。8合成政策场景通过（64MiB空间检查512→8，合成1000ms进度update1000→7含起止重复调用），仅对应模型而非实测StatFs或UI发射；串行JVM/Android源码编译中。


第6项通过（2026-10-14）：313 JVM零失败/错误/跳过及Android源码编译4分2秒通过，177输入一致；新增6 Android源码。本轮第1、2、3、6项均按顺序完成，累计新增16 JVM/21 Android源码。新独立Agent审查启动，Lint与两APK留最终统一；当前仍0.2.0/11，待独立审查后登记完整优化版本。8政策模型仅调用次数，所有真机迁移/空间/格式/查询/实际数GiB收益未运行，测试暂停。


独立审查通过（2026-10-14）：新代理zip2_independent_review未发现明确需修复问题，核对9生产/9测试/schema5及调用方。按完整四项优化登记0.2.1／12候选，updates唯一来源、两份完整分类日志已同步，十二版48条/旧十一版记录原样保留，升级近两版0.2.1/0.2.0。开始统一313 JVM/Debug与phase2Test APK/Lint/Android源码检查；未通过前不提交/标签/推送，不表示手机收益实测。


最终统一检查1分51秒通过：313 JVM零失败/错误/跳过、Debug及phase2Test APK、fresh Lint0错误17既有同ID/message警告、Android源码编译，177输入一致。APK初次aapt读取中文交付文件名报Illegal byte sequence，已保留失败日志；改查原ASCII构建路径并核对交付副本SHA完全一致，版本0.2.1/12、原包名和签名/v2、内置日志十二版48条/旧历史保留通过，未重新构建。


## 11. 0.2.1交付汇总（2026-10-14）

0.2.1／12 ZIP二轮第1、2、3、6项完整开发交付：9生产/9测试/schema5，新增16 JVM及21 Android源码；新代理独立审查无明确问题。最终313 JVM零失败/错误/跳过、Debug及phase2Test APK、Lint0错误17既有警告和Android回归源码编译通过，177输入一致；旧schema1–4不变，APK原包名/签名/v2及十二版48条日志核对通过。ADB未连接，尚未安装；手机自动测试暂停，真实迁移、格式/空间/查询/面板/数GiB提速及阶段5恢复仍待真机验收。完整版本按AGENTS自动提交及唯一v0.2.1标签推送，结果以远端读回和交付记录为准；无公开APK上传。

| 项目 | 实现与证据 | 待验范围 |
| --- | --- | --- |
| 1 排序索引 | 非唯一三列索引、显式v4→v5及完整升级链；桌面生产迁移SQL/旧行保护/100、1000、5000引用检查，4Android回归源码 | 真实Android迁移和升级耗时 |
| 2 普通新ZIP流式哈希 | 暂存写流计数/校验值，默认备份及恢复读盘保留；新图完整hash回读2→1为源码调用图；1JVM/8Android源码 | 格式/媒体/损坏/取消/恢复设备矩阵和物理I/O |
| 3 遮挡列表查询 | ZIP实际导入且面板可见时取消三个冷订阅；15JVM含千次模拟+1000/+0/恢复+1，3Room计数源码 | Compose面板、实际SQL计数及排序/手机耗时 |
| 6 复制限频 | 8MiB空间预算/200ms单调进度，8桌面政策模型，6中性Android源码；保留原余量/同步/长度/清理 | 实际StatFs次数、目标写入/同步故障、SAF与手机速度 |

第4、5项仍需真实瓶颈计时及恢复设计，未实现；第7项不改缓冲参数或PDF画质。没有手机速度百分比。Android源码全部只编译；没有运行设备测试。

证据目录：`android/verification/zip-import-speed-round2-20261014/`（不提交构建工件）；[本轮交付记录](./android/verification/zip-import-speed-round2-20261014/05-release/交付记录.md)。APK：`android/verification/zip-import-speed-round2-20261014/05-release/画师图库-0.2.1-ZIP二轮优化-20261014.apk`，SHA-256 `23f44055c0f770df97e1d252a521eba894a57aa9db05cfc047b4824ce9516ba8`。
