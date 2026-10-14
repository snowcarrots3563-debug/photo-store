# Photo Store · 私人画师图库

Android 本地图库应用，按画师和作品集整理图片、GIF、视频及 PDF 转图结果。支持相册、文件、文件夹与 ZIP／RAR／7z 导入，以及本地导出、备份和合并恢复。

当前应用版本为 **0.2.1**（versionCode 12），以 `android/app/src/main/assets/updates.json` 为版本与更新说明的唯一来源。本版优化压缩导入的排序查询、普通新 ZIP 内容校验、面板遮挡时的图库订阅及预览前复制；保留原图、恢复核验和已有整理关系。手机自动测试暂停，真实提速、设备迁移及大库／完整恢复仍单独验收。

## 项目结构

- `android/`：Kotlin、Jetpack Compose、Room 单模块应用，含 JVM 和 Android 测试源码。
- `ui-demos/`：界面原型及方案 D 截图基准。
- `design/`：应用图标设计素材。
- [开发文档](./开发文档.md)、[开发进度](./开发进度.md)、[正式开发分阶段计划](./正式开发分阶段计划.md)：需求、进度及验收边界。
- [更新日志](./更新日志.md)：应用版本记录。
- [CHANGELOG](./CHANGELOG.md)：GitHub版本发布的完整分类日志，与应用更新说明同步。

## 本地构建

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。Gradle Wrapper 已固定为 8.11.1，无需单独安装 Gradle。

用 Android Studio 打开 `android/`，或在本机创建 `android/local.properties` 配置 `sdk.dir`；该文件不会提交到 Git。

Windows PowerShell：

```powershell
Set-Location android
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-user-home'
$env:ANDROID_USER_HOME = Join-Path (Get-Location) '.android-user-home'
$env:TEMP = Join-Path (Get-Location) '.tmp'
$env:TMP = $env:TEMP
New-Item -ItemType Directory -Force $env:TEMP | Out-Null
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

Debug APK 输出在 `android/app/build/outputs/apk/debug/`。构建与 JVM 测试通过不代表真机、截图或真实图库验收完成。

## GitHub 与文件范围

GitHub 仓库：[snowcarrots3563-debug/photo-store](https://github.com/snowcarrots3563-debug/photo-store)。Git 仓库位于项目根目录，主分支为 `main`。源码、测试用例、Gradle Wrapper、固定第三方 AAR、设计素材及开发文档纳入版本管理。

构建缓存、APK、签名文件、凭据、本机 SDK 配置和 `android/verification/` 历史验证目录仅保留在本机。开发文档指向该目录的历史证据链接需要在原工作区查看；它们不会随克隆下载。测试源码使用的压缩包样本保留在 `android/app/src/androidTest/assets/`。

仓库公开可见，后续提交前请检查 `git diff --cached`，不要加入私人媒体、数据库、云服务凭据或签名密钥。云端备份尚未接入。

完整开发版本按[AGENTS.md](./AGENTS.md)自动执行测试、日志更新、提交及分支／唯一版本标签推送；普通小修改不单独发版。已有`v0.1.9`标签保留，禁止覆盖标签或force push。APK及本机验证资料继续留在本机，推送标签不自动上传APK。

## 第三方依赖

RAR／7z 使用固定的 7-Zip JBinding Android AAR。构件来源、SHA-256 和 LGPL 许可见 [依赖说明](./android/app/libs/README.md) 与同目录许可文件。

本仓库尚未为项目代码授予开源许可证；公开可见不代表已授予修改或再分发许可。第三方依赖遵循各自许可证。
