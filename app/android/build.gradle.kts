@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(ProjectConfig.JVM_VERSION)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(libs.protobuf.kotlin.lite)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.okhttp)
}

android {
    namespace = ProjectConfig.NAMESPACE
    compileSdk = ProjectConfig.Android.COMPILE_SDK
    buildToolsVersion = ProjectConfig.Android.BUILD_TOOLS_VERSION
    defaultConfig {
        applicationId = ProjectConfig.PACKAGE_NAME
        versionCode = getGitVersionCode()
        versionName = ProjectConfig.VERSION_NAME
        targetSdk = ProjectConfig.Android.TARGET_SDK
        minSdk = ProjectConfig.Android.MIN_SDK
    }
    val properties = Properties()
    runCatching { properties.load(project.rootProject.file("local.properties").inputStream()) }
    val keystorePath = properties.getProperty("KEYSTORE_PATH") ?: System.getenv("KEYSTORE_PATH")
    val keystorePwd = properties.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
    val pwd = properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules-android.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    dependenciesInfo.includeInApk = false
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "lib/*/libandroidx.graphics.path.so"
        }
    }
}

base {
    archivesName.set(
        ProjectConfig.APP_NAME + "-v" + ProjectConfig.VERSION_NAME + "(" + getGitVersionCode() + ")"
    )
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                register("java") {
                    option("lite")
                }
                register("kotlin") {
                    option("lite")
                }
            }
        }
    }
}
