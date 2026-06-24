# 时光收音机 — 项目总结

> 2026-06-25 · v1.0.37 · https://github.com/zdy010/chinese-online-radio

---

## 项目概况

面向老年人的 Android 网络收音机 + 音频库 App。技术栈：Kotlin / Compose / ExoPlayer(Media3) / Room / Hilt。

---

## Git 历史（概述）

### 初始阶段 → 代码审查
- 完善基础功能：网络收音机管理、豫剧戏曲点播（WebDAV + 123云盘）
- **代码审查**：40 项问题分析（P0/P1/P2），详见 `CODE_REVIEW.md`
- **修复**：12 项关键问题分 3 批完成，覆盖安全、性能、稳定性
- **回归修复**：4 轮测试反馈，修复了认证、导航、码率、miniplaer 残留等 bug

### 重构 → 我的音频库
- **Phase 1** 核心框架：Room 实体/DAO/Database、AudioBrowser 接口、AudioLibraryRepository（缓存 TTL）、CredentialManager（EncryptedSharedPreferences）
- **Phase 2** WebDavBrowser：封装现有 WebDavClient 为 AudioBrowser 实现
- **Phase 3** UI 替换：AudioLibraryScreen + AddSourceDialog + BrowseScreen，底部导航栏"戏曲"→"音频库"
- **Phase 4** 多来源：LocalBrowser(MediaStore) / M3uBrowser / HttpBrowser
- **Phase 5** 清理：删除 OperaScreen/OperaViewModel/YunPanConfig

### 凭证安全
- 本地 YunPanConfig.kt 保留真实凭据（skip-worktree 锁定不提交）
- GitHub 历史通过 filter-branch + gc 彻底清除敏感信息
- 密码存储使用 EncryptedSharedPreferences（AES256）

---

## 当前功能

| 模块 | 功能 |
|------|------|
| **电台** | 网络收音机，支持切换节目源、源可用性评分自动切换 |
| **音频库** | WebDAV / 本地 / M3U / HTTP 四种来源，逐级浏览 |
| **播放** | 统一走 RadioService(MediaSession)，后台/锁屏/通知栏控制 |
| **收藏** | 浏览文件时星标收藏，首页快捷入口 |
| **最近播放** | 自动记录，断点续听 |
| **设置** | 主题切换、节目源管理 |

---

## 项目结构

```
app/src/main/java/com/radio/chinese/
├── data/
│   ├── entity/          AudioSource/Cache/Favorite/Recent (Room)
│   ├── dao/             Room DAO
│   ├── local/           数据库 + DataStore
│   ├── repository/      AudioLibraryRepository
│   └── CredentialManager.kt  密码加密
├── domain/
│   ├── AudioBrowser.kt  浏览器接口
│   ├── AudioModels.kt   领域模型
│   └── browser/         WebDav/Local/M3U/Http 实现
├── service/
│   ├── RadioService.kt  MediaSession 服务
│   ├── PlayerManager.kt 播放状态管理
│   └── WebDavClient.kt  WebDAV 协议
├── ui/
│   ├── library/         音频库 UI（新）
│   ├── home/            电台列表
│   ├── player/          播放器详情
│   ├── favorites/       电台收藏
│   ├── settings/        设置
│   └── MainScreen.kt    主导航
└── di/AppModule.kt      DI 绑定

doc/                      设计文档
```

---

## 部署与发布

- GitHub: https://github.com/zdy010/chinese-online-radio
- Release 签名：debug.keystore（后续可替换为正式签名密钥）
- 版本号：自动基于 git commit 计数（1.0.37）
- 发布指南：`doc/app_store_publish_guide.md`

---

## 下一步建议

1. **发布到应用市场**（`doc/app_store_publish_guide.md`）
2. **下载管理**：统一多来源下载功能，替换旧 OperaDatabase
3. **老年用户优化**：大字体、简化操作、语音提示
4. **更多来源**：SMB/NAS、Subsonic、百度网盘等
