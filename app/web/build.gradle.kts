plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "web.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain = getByName("wasmJsMain") {
            dependencies {
                implementation(project(":shared"))
                val composeVersion = libs.versions.composeMultiplatform.get()
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation(libs.insert.koin.koin.compose)
            }
        }
    }
}

val googleWebClientIdProvider =
    providers.environmentVariable("GOOGLE_WEB_CLIENT_ID")
        .orElse(providers.gradleProperty("googleWebClientId"))
        .orElse("")

val generateGoogleConfig =
    tasks.register("generateGoogleConfig") {
        description = "Generates google-config.js with the Google Web OAuth client ID"
        val clientId = googleWebClientIdProvider
        inputs.property("GOOGLE_WEB_CLIENT_ID", clientId)
        val outputDir = layout.buildDirectory.dir("processedResources/wasmJs/main")
        outputs.dir(outputDir)

        doLast {
            val value = clientId.get()
            val dir = outputDir.get().asFile
            dir.mkdirs()
            val configFile = dir.resolve("google-config.js")
            configFile.writeText("window.iiitnrGoogleClientId = '${value}';\n")
            if (value.isBlank()) {
                logger.warn("GOOGLE_WEB_CLIENT_ID is not set — web Google Sign-In will not work. Set the env var or -PgoogleWebClientId=...")
            } else {
                logger.lifecycle("Generated google-config.js with Google Web client ID.")
            }
        }
    }

val webBrowserTaskNames =
    setOf(
        "wasmJsBrowserDevelopmentRun",
        "wasmJsBrowserProductionRun",
        "wasmJsBrowserDevelopmentWebpack",
        "wasmJsBrowserProductionWebpack",
        "wasmJsBrowserDistribution",
        "wasmJsDevelopmentExecutableCompileSync",
        "wasmJsProductionExecutableCompileSync",
    )

tasks.matching {
    it.name in webBrowserTaskNames || it.name == "wasmJsProcessResources" || it.name == "wasmJsJar"
}.configureEach {
    dependsOn(generateGoogleConfig)
}
