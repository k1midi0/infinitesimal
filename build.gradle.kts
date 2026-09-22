plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

// 版本号只有一处（gradle.properties 的 infinitesimal.version），形状也只有一种：MSI 的
// ProductVersion 就是三个数字。写坏了在这里停下，而不是等 jpackage 打包到最后一刻再报一个
// 看不懂的错。
run {
    val version = providers.gradleProperty("infinitesimal.version").orNull
        ?: error("gradle.properties 里缺 infinitesimal.version，那一个数是版本号的唯一来源（例如 1.0.0）")

    require(Regex("""\d+\.\d+\.\d+""").matches(version)) {
        "infinitesimal.version 必须是三个数字（例如 1.0.0），实际是「$version」"
    }
}
