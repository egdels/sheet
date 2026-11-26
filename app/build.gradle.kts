// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val hasReleaseSigning = !releaseKeystorePath.isNullOrEmpty() && !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty() && !releaseStorePassword.isNullOrEmpty()

val appVersion = providers.gradleProperty("appVersion").get()
val versionParts = appVersion.split('.').map { it.toInt() } + listOf(0, 0, 0)
val derivedVersionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]

android {
    namespace = "de.schliweb.sheet"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.schliweb.sheet"
        minSdk = 29
        targetSdk = 37
        versionCode = derivedVersionCode
        versionName = appVersion + providers.environmentVariable("VERSION_NAME_SUFFIX").getOrElse("")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        disable += setOf("UseKtx", "ObsoleteSdkInt")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

val checkNcnn = tasks.register("checkNcnn") {
    val layers = rootProject.layout.projectDirectory.file("native/layers.config").asFile
    val stamp = layout.projectDirectory.file("libs/ncnn.stamp").asFile
    inputs.file(layers)
    doLast {
        val lines = layers.readLines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        val text = lines.joinToString("") { "$it " }
        val want = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val have = stamp.takeIf { it.exists() }?.readText()?.trim()
        if (have != want) {
            throw GradleException("libsheet_ncnn in src/main/jniLibs is not built from the current native/layers.config (stamp ${have ?: "missing"}): run scripts/build_ncnn.sh")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNcnn) }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
