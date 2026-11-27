/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// FELISLINK-SIGNING-START
// CI injects FELISLINK_KEYSTORE_* env vars; local builds may set the same names in ~/.gradle/gradle.properties.
val releaseKeystoreFile: String? = providers.gradleProperty("felislink.keystore.file").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("FELISLINK_KEYSTORE_PASSWORD").orElse(providers.gradleProperty("FELISLINK_KEYSTORE_PASSWORD")).orNull
val releaseKeyAlias: String? = providers.environmentVariable("FELISLINK_KEY_ALIAS").orElse(providers.gradleProperty("FELISLINK_KEY_ALIAS")).orNull
val releaseKeyPassword: String? = providers.environmentVariable("FELISLINK_KEY_PASSWORD").orElse(providers.gradleProperty("FELISLINK_KEY_PASSWORD")).orNull
val hasReleaseSigning = !releaseKeystoreFile.isNullOrBlank() &&
  !releaseKeystorePassword.isNullOrBlank() &&
  !releaseKeyAlias.isNullOrBlank() &&
  !releaseKeyPassword.isNullOrBlank()
// FELISLINK-SIGNING-END

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.meta.usbvideo"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.felislink.app"
    minSdk = 31
    targetSdk = 35
    versionCode = 13
    versionName = "0.5.8"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(releaseKeystoreFile!!)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
  // 原生库改为预编译产物（本机 aarch64 无法运行官方 x86_64 NDK 工具链）
  sourceSets.getByName("main").jniLibs.srcDirs("src/main/jniLibs")
  buildFeatures {
    viewBinding = true
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity:1.9.0")
  implementation("androidx.activity:activity-ktx:1.9.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  implementation("androidx.startup:startup-runtime:1.1.1")
  testImplementation("junit:junit:4.13.2")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
  testImplementation("io.mockk:mockk-android:1.13.12")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("io.mockk:mockk-android:1.13.12")
}