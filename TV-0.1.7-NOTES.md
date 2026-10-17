# CineLive 0.1.7 电视影视墙

用户确认当贝盒子只支持 32 位，本次只构建和交付 ARM32（armeabi-v7a）电视 APK。包名仍是 `dev.cineshelf.stream`，versionCode 8，可以用原开发签名覆盖 0.1.5 / 0.1.6。

## 排版与遥控

- 黑底影视墙为主要内容。默认六列竖版海报，已有海报大小设置继续保留；空的“最近观看”标题不再占位。
- 点播、直播、设置、搜索、收藏、推送、源维护合并为一排紧凑菜单，移除原来的大型功能方块。
- 焦点进入影片时，标题、时钟、菜单及点播分类自动收起，内容扩展到整个屏幕。首次片单加载完成后自动进入影视墙；用户已经操作遥控时，延迟响应不会打断操作。
- 首页第一排影片按上键展开菜单。点播页第一排影片按上键展开分类，再按上键进入主菜单。菜单键随时唤出主菜单。
- 普通影视墙按返回键唤出主菜单；再次进入内容时保留所选影片和行位置。点播筛选或文件夹打开时，返回键仍优先关闭筛选或返回上一层。
- 选菜单时保持菜单可见，避免操作途中控件消失。没有片单、片源失败或仍在加载时，保留直播及设置入口。

## 构建

环境与播放器固定提交沿用 `TV-0.1.6-NOTES.md`。APK 架构默认改为 `armeabi-v7a`，也可以明确传入 `-PapkAbis=armeabi-v7a`。

```powershell
./gradlew.bat --no-daemon --max-workers=4 `
  "-PfongmiMediaDir=$mediaPath" "-PbuildPython=$python310Path" `
  "-PmaintenancePrivateAssetsDir=$privateAssetsPath" `
  -PapkAbis=armeabi-v7a -PchinaMavenMirror=true `
  -I gradle/media-compat.init.gradle -I gradle/china-mirror.init.gradle `
  :app:assembleLeanbackDebug :app:testLeanbackDebugUnitTest
```

签名密钥和家庭私有配置仍从外部目录注入，不进入源码 ZIP。测试使用真实 Activity、海报布局和方向键，片源在测试中提供离线样本，不访问用户的播放服务。预览图内的片单是排版测试数据。
