plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.famage.skydrop_tv_app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.famage.skydrop_tv_app"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.46"

    }

    signingConfigs {
        create("debugConfig") {
            val localKeystore = file("${rootDir}/debug.keystore")
            val rootKeystore = file("${rootDir}/../Android-app/debug.keystore")
            val homeKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storeFile = when {
                localKeystore.exists() -> localKeystore
                rootKeystore.exists() -> rootKeystore
                homeKeystore.exists() -> homeKeystore
                else -> {
                    if (!localKeystore.exists()) {
                        localKeystore.parentFile?.mkdirs()
                        try {
                            ProcessBuilder(
                                "keytool", "-genkeypair", "-v",
                                "-keystore", localKeystore.absolutePath,
                                "-storepass", "android",
                                "-keypass", "android",
                                "-alias", "androiddebugkey",
                                "-keyalg", "RSA",
                                "-keysize", "2048",
                                "-validity", "10000",
                                "-dname", "CN=Android Debug,O=Android,C=US"
                            ).inheritIO().start().waitFor()
                        } catch (_: Exception) {}
                    }
                    if (localKeystore.exists()) localKeystore else homeKeystore
                }
            }
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debugConfig")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}