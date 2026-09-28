// SPDX-FileCopyrightText: 2026 Batt Warn authors
// SPDX-License-Identifier: MIT
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.battwarn"
    compileSdk = 35

    // Generate raw-legal resources from the repo's single source of truth:
    // LICENSE (MIT), NOTICE (third-party attributions) and the full
    // Apache-2.0 text needed to convey the bundled Apache-2.0 libraries.
    val legalResDir = layout.buildDirectory.dir("generated/legal/res")
    val generateLegalResources by tasks.registering(Copy::class) {
        from(rootProject.file("LICENSE")) { rename { "mit_license.txt" } }
        from(rootProject.file("NOTICE")) { rename { "third_party_notices.txt" } }
        from(rootProject.file("LICENSES/Apache-2.0.txt")) { rename { "apache_license_2_0.txt" } }
        into(legalResDir.map { it.dir("raw") })
    }

    sourceSets {
        getByName("main") {
            res.srcDir(legalResDir)
        }
    }

    tasks.named("preBuild") {
        dependsOn(generateLegalResources)
    }

    defaultConfig {
        applicationId = "com.example.battwarn"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    // Instrumented tests (run on the emulator via `gradle connectedCheck`).
    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android platform but not of the plain JVM;
    // provide the real implementation for the pure-logic unit tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}