import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "io.github.k1midi0.infinitesimal.MainKt"

        // The application writes its log to the system console, and on Windows that
        // console is not UTF-8 by default. Without this every Chinese line comes out
        // as mojibake, which reads as a bug in the application rather than in the
        // terminal it happens to be attached to.
        jvmArgs += listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Infinitesimal"
            // 发行者。不写它 jpackage 就写「未知」——控制面板里的发行者是未知，开始菜单里的组名
            // 也是从它来的，于是快捷方式会落在一个叫「未知」的文件夹里。
            vendor = "Infinitesimal"
            // 与界面显示的那一个同源：gradle.properties 的 infinitesimal.version。
            packageVersion = providers.gradleProperty("infinitesimal.version").get()

            windows {
                menu = true
                shortcut = true
                menuGroup = "Infinitesimal"

                // MSI 靠这个 UUID 认出「这是同一个程序的新版」，而不是另一个程序。**它必须跨版本
                // 不变**：换了它，新版会和旧版并排装着、卸载项会有两条。定下来之后不要再动。
                //
                // 这一个是为 Infinitesimal 新生成的，与 Pulsus 那个不是同一个。
                upgradeUuid = "74f14e71-76c8-4d26-9b25-eaaf7797b4fa"
            }
        }
    }
}
