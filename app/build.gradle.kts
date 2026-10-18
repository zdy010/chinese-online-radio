import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val gitCommitCount: Int by lazy {
    try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    } catch (_: Exception) { 1 }
}

// Load keystore properties for release signing
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// ── 本地彩蛋/云盘真值约束 ────────────────────────────────────────
// 背景：skip-worktree 只防“真值提交入库”，不防“真值被编进 APK”。
// 而且 R8 不删字符串常量，所以给读取处加运行时门控（ENABLE_EASTER_EGG）也拦不住
// 反编译取密——唯一可靠的防线是不允许带真值的配置进入 release 构建。
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}
val eggEnabled = localProps.getProperty("enableEasterEgg", "false").toBoolean()
// 一次性豁免只能从命令行给：-PallowEggInRelease=true（不写进 local.properties，避免“误以为”长期有权限）
val allowEggInRelease = (providers.gradleProperty("allowEggInRelease").orNull ?: "") == "true"

/** 需要与仓库版本逐字一致的“真值可能所在”文件 */
val secretBearingConfigs = listOf(
    "src/main/java/com/radio/chinese/config/EasterEggConfig.kt",
    "src/main/java/com/radio/chinese/config/YunPanConfig.kt",
)

val guardReleaseConfig by tasks.registering {
    group = "verification"
    description = "release 构建前拦住带真实云盘/彩蛋凭证的本地配置，防止它们被编进可分发的 APK"
    doLast {
        val reasons = mutableListOf<String>()

        if (eggEnabled && !allowEggInRelease) {
            reasons += "local.properties 中 enableEasterEgg=true：这个开关会让真值配置生效，发布包不得携带。"
        }

        for (rel in secretBearingConfigs) {
            val file = project.file(rel)
            if (!file.exists()) continue          // 文件不存在等于无风险
            val committed = runCatching {
                providers.exec { commandLine("git", "show", "HEAD:app/$rel") }
                    .standardOutput.asText.get()
            }.getOrNull()
            if (committed == null) {              // 无仓库版本可对照 → 不放行
                reasons += "app/$rel 在仓库中没有已提交版本可对照，无法确认它是占位符。"
                continue
            }
            if (committed.trim() != file.readText().trim()) {
                reasons += "app/$rel 与仓库中已提交的版本不一致（典型原因是本地填了真凭证）。"
            }
        }

        if (reasons.isNotEmpty() && !allowEggInRelease) {
            throw GradleException(
                "\nrelease 构建被拒绝：本包不得含可被反编译取走的真实凭证。\n" +
                    reasons.joinToString("\n") { "  - $it" } +
                    "\n处理方式：\n" +
                    "  • 对外发布/上库：把上述文件恢复成仓库占位符版本（git show HEAD:app/<path> 写回）\n" +
                    "  • 自己测试必需真值：加 -PallowEggInRelease=true，产物会改名为 *-release-EGG.apk，\n" +
                    "    这个包只能自己用，不得上传应用市场或发给任何人。\n"
            )
        }
        if (allowEggInRelease) {
            logger.warn(
                "\u26a0 release 构建已豁免凭证校验：产物将命名为 *-release-EGG.apk，仅可自测，严禁上库/外发。"
            )
        }
    }
}

// AGP 的 preReleaseBuild 在变体创建后才出现，用 matching+configureEach 避免对未注册任务名的硬依赖
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(guardReleaseConfig) }

android {
    namespace = "com.radio.chinese"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.radio.chinese"
        minSdk = 26
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "1.0.$gitCommitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        setProperty("archivesBaseName", "chinese-online-radio")
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val buildType = variant.buildType.name
            val version = variant.versionName
            // 带真凭证的自测包在文件名里强制留痕，让人不可能“诋不清这个包能不能发”
            val eggTag = if (buildType == "release" && eggEnabled) "-EGG" else ""
            output.outputFileName = "chinese-online-radio-${version}-${buildType}$eggTag.apk"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // Use release signing config from keystore.properties
                // Note: STORE_FILE is relative to rootProject
                storeFile = rootProject.file(keystoreProperties["STORE_FILE"] as String)
                storePassword = keystoreProperties["STORE_PASSWORD"] as String
                keyAlias = keystoreProperties["KEY_ALIAS"] as String
                keyPassword = keystoreProperties["KEY_PASSWORD"] as String
            } else {
                // Fall back to debug keystore for development
                println("Warning: keystore.properties not found, using debug keystore for release build!")
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    defaultConfig {
        // Enable easter egg for local builds (controlled by local.properties)
        val localPropsFile = rootProject.file("local.properties")
        val localProps = Properties()
        if (localPropsFile.exists()) {
            localProps.load(localPropsFile.inputStream())
        }
        val enableEasterEgg = localProps.getProperty("enableEasterEgg", "false").toBoolean()
        buildConfigField("boolean", "ENABLE_EASTER_EGG", enableEasterEgg.toString())
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core & Activity
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Image Loading
    implementation(libs.coil.compose)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security (encrypted credential storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // JVM 单元测试：本项目此前没有任何单测能力，而标签词表 / assets 解析 / 副标题规则
    // 都属于“运行时静默错”的类别，光靠设备手点扫不出来
    testImplementation("junit:junit:4.13.2")
    // 播放页 VM 状态机测试：虚拟时间推进与接口假实现
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.13")
}
