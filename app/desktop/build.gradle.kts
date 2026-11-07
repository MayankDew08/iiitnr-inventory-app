import groovy.json.JsonSlurper
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}

val appVersion =
    providers.fileContents(rootProject.layout.projectDirectory.file("../backend/package.json"))
        .asText
        .map { packageJson ->
            (JsonSlurper().parseText(packageJson) as Map<*, *>)["version"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: error("Could not find version in backend/package.json")
        }

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.swing)
            }
            resources.srcDir("src/jvmMain/resources")
        }
    }
}

val googleConfigPropertiesFile = layout.projectDirectory.file("src/jvmMain/resources/google-desktop-config.properties").asFile

val populateGoogleConfig = tasks.register("populateGoogleConfig") {
    description = "Populates the google Config"
    group = "build"
    val fileToPopulate = googleConfigPropertiesFile

    inputs.property(
        "GOOGLE_DESKTOP_CLIENT_ID",
        providers.environmentVariable("GOOGLE_DESKTOP_CLIENT_ID").orElse("")
    )
    inputs.property(
        "GOOGLE_DESKTOP_CLIENT_SECRET",
        providers.environmentVariable("GOOGLE_DESKTOP_CLIENT_SECRET").orElse("")
    )
    inputs.property(
        "GOOGLE_DESKTOP_REDIRECT_URI",
        providers.environmentVariable("GOOGLE_DESKTOP_REDIRECT_URI").orElse("")
    )
    outputs.file(fileToPopulate)

    doLast {
        val properties = Properties()

        if (fileToPopulate.exists()) {
            fileToPopulate.inputStream().use { stream ->
                properties.load(stream)
            }
        }

        val clientId = System.getenv("GOOGLE_DESKTOP_CLIENT_ID")
        if (clientId != null && clientId.isNotBlank()) {
            properties.setProperty("google.desktop.client.id", clientId)
        }

        val clientSecret = System.getenv("GOOGLE_DESKTOP_CLIENT_SECRET")
        if (clientSecret != null && clientSecret.isNotBlank()) {
            properties.setProperty("google.desktop.client.secret", clientSecret)
        }

        val redirectUri = System.getenv("GOOGLE_DESKTOP_REDIRECT_URI")
        if (redirectUri != null && redirectUri.isNotBlank()) {
            properties.setProperty("google.desktop.redirect.uri", redirectUri)
        }

        fileToPopulate.parentFile.mkdirs()
        fileToPopulate.outputStream().use { stream ->
            properties.store(stream, "Google Desktop OAuth Configuration - Auto-generated from environment variables")
        }
    }
}

tasks.named("jvmProcessResources") {
    inputs.files(googleConfigPropertiesFile)
}

compose.desktop {
    application {
        mainClass = "com.iiitnr.inventoryapp.desktop.MainKt"

        System.getenv("JAVA_HOME")?.takeIf { File(it, "bin/jlink").exists() }?.let {
            javaHome = it
        } ?: run {
            val fallback = File("/usr/lib/jvm/java-26-openjdk")
            if (fallback.exists()) javaHome = fallback.absolutePath
        }

        nativeDistributions {
            val buildInstallers =
                project.findProperty("buildInstallers")?.toString()?.toBoolean() ?: false
            val osName = System.getProperty("os.name").lowercase()
            val formats = mutableListOf<TargetFormat>()
            if (buildInstallers) {
                if (osName.contains("win")) {
                    formats.add(TargetFormat.Msi)
                    formats.add(TargetFormat.Exe)
                }
            }
            if (osName.contains("linux")) {
                formats.add(TargetFormat.AppImage)
            }
            if (formats.isNotEmpty()) {
                targetFormats(*formats.toTypedArray())
            }

            packageName = "IIITNR Inventory App"
            packageVersion = appVersion.get()

            description = "IIITNR Inventory Management Application"
            vendor = "IIITNR"
            includeAllModules = false

            val appIconPng = project(":android").file("src/main/ic_launcher-playstore.png")
            val appIconIco = project.file("icon.ico")

            windows {
                when {
                    appIconIco.exists() -> iconFile.set(appIconIco)
                    appIconPng.exists() -> iconFile.set(appIconPng)
                }
                menu = true
                shortcut = true
            }

            linux {
                if (appIconPng.exists()) {
                    iconFile.set(appIconPng)
                }
            }
        }
    }
}
