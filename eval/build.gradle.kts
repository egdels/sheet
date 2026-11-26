// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("de.schliweb.sheet.eval.MainKt")
    applicationName = "sheet-eval"
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    val dir = layout.buildDirectory.dir("ncnn")
    doFirst {
        val lib = dir.get().asFile.listFiles { f -> f.name.startsWith("libsheet_ncnn.") }?.firstOrNull()
            ?: throw GradleException("libsheet_ncnn is not built for this machine: run scripts/build_ncnn.sh host")
        systemProperty("sheet.ncnn.library", lib.absolutePath)
    }
}
