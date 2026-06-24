# 时光收音机 — 国内应用市场发布指南

> 更新日期：2026-06-24

---

## 目录

1. [前置准备](#前置准备)
2. [生成正式签名密钥](#生成正式签名密钥)
3. [配置签名到项目](#配置签名到项目)
4. [隐私政策](#隐私政策)
5. [ICP 备案](#icp-备案)
6. [应用加固](#应用加固)
7. [各应用商店发布流程](#各应用商店发布流程)
8. [发布清单](#发布清单)

---

## 前置准备

### 必备材料

| 材料 | 说明 | 获取方式 |
|------|------|----------|
| 企业/个人开发者账号 | 实名的应用商店账号 | 各商店开发者平台注册 |
| 身份证 | 个人开发者实名认证 | — |
| ICP 备案号 | 网站备案，用于商店上架 | 阿里云/腾讯云申请 |
| 隐私政策链接 | 在线可访问的隐私说明页 | GitHub Pages / 云服务托管 |
| 应用图标 | 512x512 PNG | 跟现有图标一致或重新设计 |
| 应用截图 | 至少 4-5 张运行截图 | 模拟器或手机截图 |
| 应用简介/描述 | 中英文版 | 自己撰写 |

---

## 生成正式签名密钥

> **不要**用之前的 `debug.keystore`。应用市场上架必须用自己生成的正式密钥，**密钥文件丢失=永远无法更新应用**。

```bash
# 在项目根目录执行
keytool -genkey -v -keystore release-key.jks \
  -alias radio_app \
  -keyalg RSA -keysize 2048 -validity 36500 \
  -storetype pkcs12
```

- **有效期**: 建议填 100 年（36500 天），一次配好终身不用换
- **密码**: 记住，丢了应用就废了
- **别名**: `radio_app`（可自定义）
- **存放**: 放到 `app/release-key.jks`，**不要提交到 git**

---

## 配置签名到项目

在 `app/build.gradle.kts` 的 `android` 块中替换之前 debug.keystore 的配置：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("release-key.jks")
            storePassword = "你的密钥库密码"
            keyAlias = "radio_app"
            keyPassword = "你的密钥密码"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(...)
        }
    }
}
```

> ⚠️ **安全建议**：不要把密码直接写在 build.gradle.kts 里。更好的方式：
> - 设环境变量，运行时读取
> - 或者用 `~/.gradle/gradle.properties` 存放

然后在 `~/.gradle/gradle.properties` 中添加：

```properties
RELEASE_STORE_FILE=release-key.jks
RELEASE_STORE_PASSWORD=xxx
RELEASE_KEY_ALIAS=radio_app
RELEASE_KEY_PASSWORD=xxx
```

build.gradle.kts 中改为：

```kotlin
signingConfigs {
    create("release") {
        storeFile = file(project.findProperty("RELEASE_STORE_FILE") as? String ?: "release-key.jks")
        storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as? String ?: ""
        keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as? String ?: ""
        keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as? String ?: ""
    }
}
```

---

## 隐私政策

**应用市场强制要求**：每个应用必须提供可公开访问的隐私政策网页。

### 快速方案（免费）

1. 用 GitHub Pages 托管：
   - 在 GitHub 上新建仓库 `radio-privacy`
   - 创建一个 `index.html` 说明隐私政策
   - 开启 GitHub Pages → 得到一个 `https://你的用户名.github.io/radio-privacy/` 链接
   - 把这个链接填到各应用商店的"隐私政策"字段

### 隐私政策需包含的内容

- 收集哪些信息（设备信息、日志、网络状态）
- 如何使用这些信息
- 是否共享给第三方（本 App 不用）
- 用户如何控制/删除数据
- 联系方式

### 模板参考

你的 App 功能很简单——网络收音机 + 本地戏曲下载，不涉及用户注册、通讯录、位置等敏感权限。隐私政策可以很精简：

> 时光收音机不收集任何个人身份信息。App 仅访问网络以获取广播流媒体，下载的音频文件存储在设备本地。不会向任何第三方共享数据。

---

## ICP 备案

> 注意：2025 年起，华为、小米、应用宝等主流市场**要求提供** ICP 备案号。没有 ICP 备案的应用将无法上架或会被下架。

### 备案流程

1. 购买云服务器或虚拟主机（阿里云/腾讯云/华为云均可）
2. 在云服务商的备案系统提交申请
3. 通常需要 5-20 个工作日审核
4. 拿到 ICP 备案号后在应用商店填写

### 免备案方案

如果暂时没有备案，可以考虑：
- **应用宝（腾讯）**：对个人开发者相对宽松
- **酷安**：门槛较低
- 先上架再到有服务器后补备案

---

## 应用加固

国内大市场基本都要求对 APK 做加固（防逆向、防篡改）。

### 常用方案

| 方案 | 费用 | 特点 |
|------|------|------|
| **腾讯云加固** | 免费 | 与应用宝配合好，直接在应用宝后台操作 |
| **360 加固** | 免费版可用 | 支持批量加固，市场覆盖广 |
| **娜迦加固** | 收费 | 兼容性好 |
| **顶象** | 免费版可用 | 阿里系 |

### 加固流程

```
APK → 加固工具处理 → 得到加固后的 APK → 重新签名 → 上传市场
```

**注意**：加固后再签一次名（用你的正式密钥），因为加固工具会破坏原有签名。

---

## 各应用商店发布流程

### 1. 华为 AppGallery

- **注册**: https://developer.huawei.com
- **费用**: 个人开发者免费
- **要求**: 
  - 企业开发者需营业执照
  - 需提供隐私政策链接
  - 需适配华为 HMS 推送（可选）
- **审核周期**: 1-3 个工作日
- **特色**: 华为设备用户量大，推荐优先上

### 2. 应用宝（腾讯）

- **注册**: https://open.tencent.com
- **费用**: 免费
- **要求**: 个人即可注册
- **审核周期**: 1-3 个工作日
- **特色**: 覆盖所有安卓手机，装机量大

### 3. 小米应用商店

- **注册**: https://dev.mi.com
- **费用**: 个人开发者免费
- **要求**: 个人开发者实名认证
- **审核周期**: 1-2 个工作日

### 4. OPPO 软件商店

- **注册**: https://open.oppomobile.com
- **费用**: 免费
- **要求**: 个人开发者需提供身份证
- **审核周期**: 1-3 个工作日

### 5. vivo 应用商店

- **注册**: https://dev.vivo.com.cn
- **费用**: 免费
- **要求**: 个人开发者实名认证
- **审核周期**: 1-2 个工作日

### 6. 360 手机助手

- **注册**: https://dev.360.cn
- **费用**: 免费
- **要求**: 个人开发者即可
- **审核周期**: 1-3 个工作日

### 7. 百度手机助手

- **注册**: https://app.baidu.com
- **费用**: 免费
- **要求**: 企业/个人均可
- **审核周期**: 3-5 个工作日

### 8. 酷安（CoolAPK）

- **注册**: https://coolapk.com
- **费用**: 免费
- **要求**: 门槛最低，用户偏 IT 向
- **审核周期**: 1-2 个工作日
- **适合**: 早期验证和获取真实用户反馈

---

## 版本号管理

你的项目目前配置（`app/build.gradle.kts`）：

```kotlin
versionCode = gitCommitCount       // 自动递增
versionName = "1.0.$gitCommitCount" // 1.0.27, 1.0.28, ...
```

这个策略适合持续更新。上架后每次修复 bug 或加功能，重新 build 发布即可。

---

## 发布清单

### 每次发布前检查

- [ ] versionCode 比上次大（已自动化）
- [ ] 用正式签名编译（不是 debug.keystore）
- [ ] 开启 ProGuard 混淆
- [ ] 隐私政策网页可访问
- [ ] APK 经过加固
- [ ] 加固后重新签名
- [ ] 在真机上完整测试一遍核心功能
- [ ] 准备更新日志（新功能/修复内容）

### 建议上架顺序

1. **酷安** → 最快验证，获取首批用户反馈
2. **应用宝** → 覆盖最广
3. **华为** → 你用的就是华为机，优先适配
4. **小米 / OPPO / vivo** → 三大主流
5. **360 / 百度** → 补充覆盖
