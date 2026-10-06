import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.aloys23.komiraquake"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aloys23.komiraquake"
        minSdk = 33
        targetSdk = 37
        // 缺省值供本地开发；CI 推 tag 时用 -PversionName/-PversionCode 覆盖，
        // 使产物内的版本与 tag 一致（否则发 v1.0.4 仍会打出 versionName=1.0.3）。
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "4").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.3"
        testInstrumentationRunner = "com.aloys23.komiraquake.IsolatedTestRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("KOMIRA_KEYSTORE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("KOMIRA_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("KOMIRA_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("KOMIRA_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 仅当提供了签名属性时才启用，避免无 keystore 时构建失败。
            if (providers.gradleProperty("KOMIRA_KEYSTORE").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("test").resources.srcDir("src/main/assets")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    // Explicit: map background capture and native backdrop filtering.
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    // 本地 JVM 单测需要真实的 org.json 实现（android.jar 中的会被 stub）
    testImplementation(libs.json)
}
