# 中文收音机 (ChineseRadio)

一款基于网络流媒体的 Android 中文收音机应用，支持收听中国各地广播电台节目。

## 功能特性

- 内置 40+ 中文电台：央广系列（中国之声、经济之声、音乐之声等）及各省市广播电台
- 电台分类浏览：新闻、音乐、交通、文艺、体育、财经、综合
- 搜索功能：按电台名称或频率搜索
- 收藏管理：收藏常用电台，快速访问
- 后台播放：支持后台音频播放，不占用前台
- 通知栏控制：在通知栏显示播放信息，支持播放/暂停/切换
- 睡眠定时器：设置 15/30/45/60 分钟后自动停止播放
- 耳机线控：支持耳机按键控制播放
- 主题切换：浅色/深色/跟随系统，支持 Material You 动态配色

## 技术架构

| 组件 | 技术选型 |
|------|---------|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose + Material 3 |
| 音频播放 | Media3 (ExoPlayer) + MediaSession |
| 架构模式 | MVVM + Clean Architecture |
| 依赖注入 | Hilt |
| 本地存储 | Room + DataStore |
| 图片加载 | Coil |
| 导航 | Compose Navigation |

## 项目结构

```
app/src/main/java/com/radio/chinese/
├── data/
│   ├── local/          # Room 数据库、DataStore 偏好设置
│   ├── repository/     # 数据仓库 (StationRepository, FavoriteRepository)
│   └── model/          # 数据模型
├── domain/
│   └── model/          # 领域模型 (RadioStation, Category, ProgramInfo)
├── service/
│   ├── RadioService.kt # MediaSessionService 后台播放服务
│   └── PlayerManager.kt# MediaController 桥接 UI 与 Service
├── timer/
│   └── SleepTimer.kt   # 睡眠定时器
├── ui/
│   ├── home/           # 首页 - 电台列表、搜索、分类筛选
│   ├── player/         # 全屏播放页面
│   ├── category/       # 分类浏览页面
│   ├── favorites/      # 收藏列表页面
│   ├── settings/       # 设置页面
│   ├── navigation/     # 导航路由
│   └── theme/          # Material 3 主题
├── di/                 # Hilt 依赖注入模块
├── RadioApp.kt         # Application 入口
└── MainActivity.kt     # 主 Activity
```

## 电台数据

内置电台列表存储于 `assets/radio_stations.json`，包含央广及各省市广播电台的公开流媒体地址。

支持的流媒体协议：
- HLS (m3u8)
- AAC 流
- MP3 流

## 构建与运行

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+（Android Studio 自带 JBR）
- Android SDK Platform 35
- Gradle 8.9+

### 命令行构建

```bash
# 设置 JAVA_HOME（如果使用 Android Studio 自带 JDK）
# Windows:
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

# 编译 Debug 版本
./gradlew assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Android Studio 构建

1. 打开 Android Studio，选择 "Open an existing project"
2. 选择本项目根目录
3. 等待 Gradle 同步完成
4. 点击 Run 或按 Shift+F10 运行

## 系统要求

- Android 8.0 (API 26) 及以上
- 需要网络连接以播放电台

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

## 参考项目

本项目在开发过程中参考了以下开源项目：

| 项目 | 说明 | 协议 |
|------|------|------|
| [Swift-Radio-Android](https://github.com/fethica/Swift-Radio-Android) | 技术架构参考：Kotlin + Jetpack Compose + Media3 的完整实现方案，电台 JSON 数据格式设计 | MIT License |
| [Transistor](https://github.com/y20k/transistor) | 功能交互参考：简洁收音机应用的 UI 交互设计 | MIT License |
| [RadioDroid](https://github.com/segler-alex/RadioDroid) | 功能参考：网络电台浏览、收藏管理等功能设计 | GPL-3.0 |
| [fanmingming/live](https://github.com/fanmingming/live) | 数据来源：中国各地广播电台流媒体地址及台标图片资源 | GPL-3.0 |
