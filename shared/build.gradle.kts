import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    jvm()

    android {
        namespace = "io.github.k1midi0.infinitesimal.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            // 出厂预设包（`composeResources/files/builtin-presets.json`）。它是**数据不是代码**：一批
            // 形状有几千行网格文本，放进源码会把源码淹掉，而它又是可以重扫的东西（spike/PresetSweep）。
            implementation(libs.compose.components.resources)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}

// 联机 spike 是按次开启的：它要一个真凭据和一次网络访问，所以密钥文件由命令行传入，既不进仓库
// 也不进任何配置。不带这个参数时 spike 什么都不做，只打印一行说明。
tasks.withType<Test>().configureEach {
    systemProperty(
        "infinitesimal.liveKeyFile",
        providers.gradleProperty("infinitesimalLiveKeyFile").getOrElse(""),
    )
    // 第二步的思考强度。**它只能量出来，不能想出来**（§14.19）：同一个场景换一档，延迟差十倍。
    // 与密钥同一个理由留在命令行上：它是探针的旋钮，不是产品的配置。
    systemProperty(
        "infinitesimal.effort",
        providers.gradleProperty("infinitesimalEffort").getOrElse(""),
    )
    // 出厂预设包扫到哪个文件去。默认是源码树里那个位置（见 spike/PresetSweep），这个旋钮只在
    // "想先扫到别处看看"时才用得上。
    systemProperty(
        "infinitesimal.presetFile",
        providers.gradleProperty("infinitesimalPresetFile").getOrElse(""),
    )
    // ── 参考剧本那一层（§14.55） ──────────────────────────────────────────────────
    // 拆一部小说（spike/ScriptDigest）：原文在哪、写到哪个库文件、这一次最多拆几段。
    // 与密钥同一个理由留在命令行上：它们是"这一次要拆哪本书"的输入，不是产品的配置。
    systemProperty(
        "infinitesimal.novelFile",
        providers.gradleProperty("infinitesimalNovelFile").getOrElse(""),
    )
    systemProperty(
        "infinitesimal.scriptsFile",
        providers.gradleProperty("infinitesimalScriptsFile").getOrElse(""),
    )
    systemProperty(
        "infinitesimal.digestChunks",
        providers.gradleProperty("infinitesimalDigestChunks").getOrElse(""),
    )
    // 开局填充那个探针跑几遍（spike/OpeningScriptsProbe）。**它一次要一分钟上下**，所以默认值写在
    // 探针里（3），这个旋钮是"今天只想看一遍"时用的。
    systemProperty(
        "infinitesimal.openingRuns",
        providers.gradleProperty("infinitesimalOpeningRuns").getOrElse(""),
    )
}
