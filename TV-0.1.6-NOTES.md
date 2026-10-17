# CineLive 0.1.6 TV 修复

基于 Google Drive 的 `CineLive-source-0.1.5-dev.zip`，包名仍为 `dev.cineshelf.stream`，版本号为 `0.1.6-tv-dev`（versionCode 7）。本次交付仅构建电视版。

## 使用变化

- 电视版首次打开新版时切换为纯黑背景，文字为白色；焦点采用亮青色粗边框及深色填充。之后在设置中主动选择其他壁纸仍会保留。
- 首页及点播分类页增加固定的“点播 / 直播 / 设置”导航。左右键移动，确定键进入，下键进入内容，上键返回导航。
- 主导航不受片源加载、空内容和失败状态影响。首页异步加载完成、点播分类延迟切换时不抢走导航焦点。
- 首页内容区域按返回键先回到主导航。没有直播源时，直播入口进入内置源选择页。

## 构建

原工程要求 JDK 21、Gradle 9.7.1、Android SDK 37.0、Build Tools 36.0.0、Python 3.10。Gradle Wrapper 会按 `gradle/gradle-daemon-jvm.properties` 下载其指定的 JBR 21。播放器源码需要固定在 FongMi/media 提交 `3c2cbe8ac742c2fe15eff52f03eeb3b1b648848d`，不能随意替换成最新提交。

Windows PowerShell 7 示例（先把变量设为本机路径）：

```powershell
$env:JAVA_HOME = $jdkPath
$env:ANDROID_HOME = $sdkPath
$env:GRADLE_USER_HOME = $gradleCachePath
./gradlew.bat --no-daemon --max-workers=4 `
  "-PfongmiMediaDir=$mediaPath" "-PbuildPython=$python310Path" `
  -PchinaMavenMirror=true `
  -I gradle/media-compat.init.gradle -I gradle/china-mirror.init.gradle `
  :app:assembleLeanbackDebug :app:testLeanbackDebugUnitTest
```

使用网络代理时 Java 需要单独配置 `http.proxyHost/http.proxyPort/https.proxyHost/https.proxyPort`。私有家庭维护配置通过 `-PmaintenancePrivateAssetsDir=<外部目录>` 注入。签名和私有配置不放进源码包。最终 APK 使用与旧版相同的外部开发签名重新签署，覆盖安装需要包名相同、签名相同且版本号更高。

## 回归范围

`TvNavigationTest` 使用真实 TV 布局与 Activity，发送遥控方向键及确定键，覆盖加载/空状态导航、直播目标、点播分类到直播及设置、异步焦点恢复、黑底升级和后续壁纸选择。

自动化验证不能替代当贝盒子上的真实遥控和播放测试。设备的 CPU 位数不等于 Android 系统支持的 ABI；无法仅从型号页确定能否安装 ARM64。未确认系统 ABI 时优先使用 ARM32 安装包。
