import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")      // Firebase や AdMob などの Google サービス連携に必要
}

// local.properties を読み込む
val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

// local.properties から APIキーや署名情報を取得
val mapsApiKey = localProps.getProperty("MAPS_API_KEY") ?: ""
val admobAppId = localProps.getProperty("ADMOB_APP_ID") ?: ""
val admobNativeId = localProps.getProperty("ADMOB_NATIVE_ID") ?: ""
val admobRewardId = localProps.getProperty("ADMOB_REWARD_ID") ?: ""

// keystore 関連情報（署名付き AAB 用）
val keystoreFile = localProps.getProperty("KEYSTORE_FILE")?.let { file(it) }
val keystorePassword = localProps.getProperty("KEYSTORE_PASSWORD") ?: ""
val keyAlias = localProps.getProperty("KEY_ALIAS") ?: ""
val keyPassword = localProps.getProperty("KEY_PASSWORD") ?: ""

android {
    namespace = "com.ykun.MockLocation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ykun.MockLocation"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AndroidManifest.xml 内で参照できる文字列リソースを定義
        // 例: android:value="@string/google_maps_key" という形で利用可能
        resValue("string", "google_maps_key", mapsApiKey)
        resValue("string", "admob_app_id", admobAppId)
        resValue("string", "admob_native_id", admobNativeId)
        resValue("string", "admob_reward_id", admobRewardId)
    }

    // 署名付きビルド設定（Playにアップロードする際に必須）
    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keyAlias
            keyPassword = keyPassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true // 難読化
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.0.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.play.services.maps)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Google Maps SDK（地図表示用）
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // 位置情報の取得API（FusedLocationProviderClientなど）
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // AdMob 広告表示（ネイティブ・報酬型広告など）
    implementation("com.google.android.gms:play-services-ads:24.4.0")

    // Import the Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-analytics")
}