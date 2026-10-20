import java.util.Properties

// 应用模块构建配置
plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("com.google.devtools.ksp")
}

// 本机签名凭据不纳入版本控制；没有配置时仍可构建 debug 或未签名 release。
val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("signing.properties")
if (signingPropertiesFile.isFile) {
    signingPropertiesFile.inputStream().use { signingProperties.load(it) }
}

android {
    namespace = "com.lvjian.app"
    // Android 15 SDK 即可覆盖 Android 16 真机（targetSdk 35 在 API 36 设备上正常运行）
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lvjian.app"
        minSdk = 26          // 通知渠道/搁置等 API 从 26 起完整；S50 是 Android 16，远高于此
        targetSdk = 35
        versionCode = 56
        versionName = "0.10.9"   // 一键整理实时条数进度；当前周标题改为本周
    }

    // 从本机配置读取签名，不在公开源码中保存密码或密钥库。
    signingConfigs {
        if (signingPropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 首版个人自用：不做混淆，便于真机排查
            isMinifyEnabled = false
            // release 必须签名才能安装；个人侧载用本地生成的长期密钥
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM 统一版本
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Room 本地数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore：AI 配置等小型偏好存储
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // OkHttp：AI API 调用
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // core-ktx：Drawable.toBitmap 等（应用图标转 ImageBitmap 用）
    implementation("androidx.core:core-ktx:1.13.1")

    // ML Kit 中文文字识别（bundled 变体：模型打包进 APK，国行无谷歌服务也能用）
    // 注意不要用 play-services-mlkit-* 变体——依赖谷歌服务框架，无 GMS 的国行 ROM 直接崩
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // Task.await()：把 ML Kit 的 Task 桥接进协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Coil：对话页发送图片的本地预览
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JVM 单元测试：通知处置策略（TakeoverPolicy）等纯逻辑
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
