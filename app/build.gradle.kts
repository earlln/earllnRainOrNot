import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// API 키는 코드에 넣지 않습니다. local.properties 또는 환경변수(GitHub Actions Secrets)로 주입합니다.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String, default: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name))?.trim()?.takeIf { it.isNotEmpty() } ?: default

// 공개 배포용 릴리스 서명. 키스토어 파일 경로와 비밀번호는 환경변수(GitHub Secrets)로만 받는다.
val releaseKeystore = secret("RELEASE_KEYSTORE_FILE").takeIf { it.isNotEmpty() }?.let { rootProject.file(it) }

android {
    namespace = "com.earlln.rainornot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.earlln.rainornot"
        minSdk = 24
        targetSdk = 34
        versionCode = secret("VERSION_CODE", "2").toInt()
        versionName = secret("VERSION_NAME", "2.0")

        buildConfigField("String", "GEMINI_API_KEY", "\"${secret("GEMINI_API_KEY")}\"")
        buildConfigField("String", "ITS_API_KEY", "\"${secret("ITS_API_KEY")}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${secret("GEMINI_MODEL", "gemini-3.5-flash")}\"")
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 공개 배포 APK 에는 Gemini·ITS 키를 넣지 않는다 (사용자가 설정 화면에서 각자 발급받아 입력)
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "ITS_API_KEY", "\"\"")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
}
