import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.animation)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

android {
    namespace = "com.velocity.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.velocity.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val localReleaseKeystore = file("velocity-release.jks")
            val buildKeystore = file("build/velocity-release.jks")
            val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")

            when {
                localReleaseKeystore.exists() -> {
                    storeFile = localReleaseKeystore
                    storePassword = System.getenv("VELOCITY_KEYSTORE_PASSWORD") ?: "velocity"
                    keyAlias = System.getenv("VELOCITY_KEY_ALIAS") ?: "velocity"
                    keyPassword = System.getenv("VELOCITY_KEY_PASSWORD") ?: "velocity"
                }
                buildKeystore.exists() -> {
                    storeFile = buildKeystore
                    storePassword = System.getenv("VELOCITY_KEYSTORE_PASSWORD") ?: "velocity"
                    keyAlias = System.getenv("VELOCITY_KEY_ALIAS") ?: "velocity"
                    keyPassword = System.getenv("VELOCITY_KEY_PASSWORD") ?: "velocity"
                }
                debugKeystore.exists() -> {
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                else -> {
                    initWith(getByName("debug"))
                }
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        manifest.srcFile("src/androidMain/AndroidManifest.xml")
        res.srcDirs("src/androidMain/res")
        jniLibs.srcDirs("src/androidMain/jniLibs", "../native/jniLibs")
    }
}

compose.desktop {
    application {
        mainClass = "com.velocity.app.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Msi,
                TargetFormat.Deb,
            )
            packageName = "Velocity"
            packageVersion = "1.0.0"
        }
    }
}

tasks.register("jvmTest") {
    dependsOn("desktopTest")
}

