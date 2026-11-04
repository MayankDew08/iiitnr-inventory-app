import groovy.json.JsonSlurper
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.sqldelight)
}

val packageJsonFile = rootProject.layout.projectDirectory.file("../backend/package.json")
val generatedVersionDir = layout.buildDirectory.dir("generated/source/appVersion/commonMain/kotlin")

val isDebugBuild = (project.findProperty("appDebug") as String?)?.toBoolean() ?: false
val generatedBuildFlagsDir =
    layout.buildDirectory.dir("generated/source/buildFlags/commonMain/kotlin")

val generateBuildFlagsKt =
    tasks.register("generateBuildFlagsKt") {
        description = "Generates BuildFlags for the App"
        val debug = isDebugBuild
        val outputDir = generatedBuildFlagsDir.get().asFile

        inputs.property("isDebug", debug)
        outputs.dir(outputDir)

        doLast {
            val flagsFile = outputDir.resolve("com/iiitnr/inventoryapp/data/BuildFlags.kt")
            outputDir.deleteRecursively()
            flagsFile.parentFile.mkdirs()
            flagsFile.writeText(
                """
                package com.iiitnr.inventoryapp.data

                internal object BuildFlags {
                    const val IS_DEBUG = $debug
                }
                """.trimIndent() + "\n",
            )
        }
    }

val generateVersionKt =
    tasks.register("generateVersionKt") {
        description = "Generates Version for the App"
        val versionInput = packageJsonFile.asFile
        val versionOutputDir = generatedVersionDir.get().asFile

        inputs.file(versionInput)
        outputs.dir(versionOutputDir)

        doLast {
            val packageJson = versionInput.readText()
            val version =
                (JsonSlurper().parseText(packageJson) as Map<*, *>)["version"]
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Could not find version in backend/package.json")
            val versionFile =
                versionOutputDir.resolve("com/iiitnr/inventoryapp/data/GeneratedVersion.kt")

            versionOutputDir.deleteRecursively()
            versionFile.parentFile.mkdirs()
            versionFile.writeText(
                """
                package com.iiitnr.inventoryapp.data

                internal object GeneratedVersion {
                    const val CURRENT_VERSION = "$version"
                }
                """.trimIndent() + "\n",
            )
        }
    }

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    android {
        namespace = "com.iiitnr.inventoryapp.shared"
        compileSdk = 37
        withHostTest {}
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    val isMac =
        org.gradle.internal.os.OperatingSystem
            .current()
            .isMacOsX
    if (isMac) {
        val xcf = XCFramework("shared")
        val iosTargets = listOf(iosArm64(), iosSimulatorArm64())
        iosTargets.forEach {
            it.binaries.framework {
                baseName = "shared"
                isStatic = true
                xcf.add(this)
            }
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain =
            sourceSets.getByName("commonMain") {
                kotlin.srcDir(generateVersionKt)
                kotlin.srcDir(generateBuildFlagsKt)

                dependencies {
                    implementation(libs.ui.tooling.preview)
                    implementation(libs.runtime)
                    implementation(libs.ui)
                    implementation(libs.foundation)
                    implementation(libs.material3)
                    implementation(libs.components.resources)

                    implementation(libs.material.icons.extended)

                    implementation(libs.ktor.client.core)
                    implementation(libs.ktor.client.content.negotiation)
                    implementation(libs.ktor.serialization.kotlinx.json)

                    implementation(libs.kotlinx.serialization.json)

                    implementation(libs.kotlinx.coroutines.core)

                    implementation(libs.navigation.compose)

                    implementation(libs.coil.compose)
                    implementation(libs.coil.network)
                    implementation(libs.qrose)
                    implementation(libs.sqldelight.runtime)
                    implementation(libs.sqldelight.coroutines.extensions)
                    implementation(libs.kotlinx.datetime)

                    implementation(libs.insert.koin.koin.compose)
                    implementation(libs.insert.koin.koin.compose.viewmodel)
                    implementation(libs.androidx.lifecycle.viewmodel.compose)
                }
            }

        val commonTest =
            sourceSets.getByName("commonTest") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }

        val androidMain =
            sourceSets.getByName("androidMain") {
                dependencies {
                    api("io.insert-koin:koin-android:${libs.versions.koin.get()}")
                    implementation(libs.kotlinx.coroutines.android)
                    implementation(libs.sqldelight.driver.android)
                    implementation(libs.ktor.client.android)
                    implementation(libs.androidx.activity.compose)
                    implementation(libs.androidx.datastore.preferences)
                    implementation(libs.androidx.credentials)
                    implementation(libs.androidx.credentials.play.services.auth)
                    implementation(libs.googleid)
                    implementation(libs.camerak.core)
                    implementation(libs.camerak.qr)
                    implementation(libs.moko.permissions)
                }
            }

        if (isMac) {
            val iosMain = findByName("iosMain") ?: create("iosMain")
            iosMain.dependsOn(commonMain)
            getByName("iosArm64Main").dependsOn(iosMain)
            getByName("iosSimulatorArm64Main").dependsOn(iosMain)
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.driver.native)
                implementation(libs.camerak.core)
                implementation(libs.camerak.qr)
                implementation(libs.moko.permissions)
            }
        }

        val jvmMain =
            sourceSets.getByName("jvmMain") {
                dependencies {
                    implementation(libs.ktor.client.cio)
                    implementation(libs.sqldelight.driver.sqlite)
                    implementation(libs.kotlinx.coroutines.swing)
                }
            }

        val wasmJsMain =
            sourceSets.getByName("wasmJsMain") {
                dependencies {
                    implementation(libs.ktor.client.js)
                    implementation(libs.sqldelight.driver.web.worker)
                }
            }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.iiitnr.inventoryapp.db")
        }
    }
}

compose.resources {
    packageOfResClass = "com.iiitnr.inventoryapp.shared"
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
}

ktlint {
    filter {
        exclude("**/build/**")
        exclude("**/generated/**")
    }
}

dependencies {
    val composeVersion = libs.versions.composeMultiplatform.get()
    add("androidRuntimeClasspath", "org.jetbrains.compose.ui:ui-tooling:$composeVersion")
}
