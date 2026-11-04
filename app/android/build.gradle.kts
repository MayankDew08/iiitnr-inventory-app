import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val appVersion =
    providers
        .fileContents(rootProject.layout.projectDirectory.file("../backend/package.json"))
        .asText
        .map { packageJson ->
            (JsonSlurper().parseText(packageJson) as Map<*, *>)["version"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: error("Could not find version in backend/package.json")
        }

fun versionCodeFrom(version: String): Int =
    version
        .split('.')
        .map { it.toInt() }
        .let { parts ->
            val major = parts.getOrElse(0) { 0 }
            val minor = parts.getOrElse(1) { 0 }
            val patch = parts.getOrElse(2) { 0 }
            major * 10_000 + minor * 100 + patch
        }

android {
    namespace = "com.iiitnr.inventoryapp"
    compileSdk = 37

    val hasReleaseSigning =
        project.hasProperty("RELEASE_KEYSTORE_PATH") ||
            System.getenv("RELEASE_KEYSTORE_PATH").orEmpty().isNotBlank()
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile =
                    file(
                        project.findProperty("RELEASE_KEYSTORE_PATH") as String?
                            ?: System.getenv("RELEASE_KEYSTORE_PATH")!!,
                    )
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD")?.toString()
                    ?: System.getenv("RELEASE_STORE_PASSWORD").orEmpty()
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS")?.toString()
                    ?: System.getenv("RELEASE_KEY_ALIAS").orEmpty()
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD")?.toString()
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.iiitnr.inventoryapp"
        minSdk = 24
        targetSdk = 37
        versionName = appVersion.get()
        versionCode = versionCodeFrom(versionName!!)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        manifestPlaceholders["appName"] = "IIITNR Inventory App"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appName"] = "IIITNR Inventory App (Debug)"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                if (hasReleaseSigning) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val verName = appVersion.get()
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                output.outputFileName.set("com.iiitnr.inventoryapp-$verName.apk")
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.koin.core)
    implementation(libs.koin.android.provider)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.datetime)
    implementation(libs.play.services.auth)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
