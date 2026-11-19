import org.spotube.PluginAbility
import org.spotube.PluginCapability
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.zipline.gradle.plugin)
    alias(libs.plugins.spotubeGradle)
}

spotubePlugin {
    name = "KuGou Lite"
    version = "0.1.1"
    apiVersion = "0.1.0"
    description = "Spotube music service provider plugin for KuGou Lite"
    contact = "Local build"
    repository = "https://github.com/GrayEnergy/spotube-plugin-kugou-lite"
    bugs = "https://github.com/GrayEnergy/spotube-plugin-kugou-lite/issues"
    author = "local.kugou"
    capabilities = listOf(
        PluginCapability.NETWORK_REQUESTS,
        PluginCapability.PERSISTENT_STORAGE,
        PluginCapability.WEBVIEW
    )
    abilities = listOf(
        PluginAbility.METADATA,
        PluginAbility.AUDIO,
        PluginAbility.LYRICS,
        
    )
    // Hosts whose WebView cookies this plugin reads (webViewAPI.getCookies) or whose pages it runs
    // scripts on (webViewAPI.evaluateJavaScript). Spotube refuses both for any other host.
    // "example.com" matches only that host; "*.example.com" matches its subdomains, not example.com.
    cookieDomains = emptyList()
    license = "AGPL-3.0-or-later"
}

kotlin {
    applyDefaultHierarchyTemplate()

    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        nodejs()
        binaries.executable()
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.zipline.core)
            implementation(libs.spotube.plugin.interfaces)
            implementation(libs.semver)
        }

        jsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jsMain.dependencies {
        }

        jvmTest.dependencies {
            implementation("app.cash.zipline:zipline-loader:1.27.0")
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)





        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.swing)
        }

    }
}

zipline {
    mainFunction.set("local.kugou.main")
}

plugins.withType<YarnPlugin> {
    the<YarnRootExtension>().yarnLockAutoReplace = true
}




tasks.named("jvmTest") {
    dependsOn("packageProductionPlugin")
    inputs.files(tasks.named("packageProductionPlugin"))
}
tasks.withType<Zip>().matching { it.name.startsWith("package") && it.name.endsWith("Plugin") }.configureEach {
    from(rootProject.file("LICENSE-MyGo"))
    from(rootProject.file("LICENSE-QR"))
}

tasks.named("generatePluginJson") { inputs.file(project.buildFile) }
