plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ycm50.subscribemeter"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ycm50.subscribemeter"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // 发布签名:本地用 keystore.jks / CI 用 Secrets 注入的同名文件,两者都不入库。
    signingConfigs {
        create("release") {
            val storePath = System.getenv("SIGNING_STORE_FILE") ?: "keystore.jks"
            val store = rootProject.file(storePath)
            if (store.exists()) {
                storeFile = store
                storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "123456"
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "key0"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有签名库就用它签名;没有则退化为 debug 签名(仅本地临时用)。
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // Compose BOM 2026.06.01 → ui 1.11.4:与本机 compileSdk 36 / AGP 8.13 兼容
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
