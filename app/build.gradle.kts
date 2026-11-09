plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sdm.plus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sdm.plus"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // Fixed key from GitHub secrets (see README): every build is signed the same way,
    // so new versions install over old ones. Without the secrets, the debug key is used.
    val ksPath = System.getenv("SDM_KEYSTORE_FILE")
    val ksPass = System.getenv("SDM_KEYSTORE_PASSWORD")
    val hasKey = !ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && file(ksPath).exists()
    signingConfigs {
        if (hasKey) {
            create("sdm") {
                storeFile = file(ksPath!!)
                storePassword = ksPass
                keyAlias = "sdm"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        val signing = if (hasKey) signingConfigs.getByName("sdm") else signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            // yt-dlp's JSON mapper uses reflection; shrinking would break it.
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    // One APK per phone CPU type keeps each APK much smaller.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    packaging {
        jniLibs {
            // Required by youtubedl-android (extracts python/ffmpeg at runtime).
            useLegacyPackaging = true
            pickFirsts += "**/libc++_shared.so"
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
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Torrent engine (libtorrent)
    val lt4j = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt4j")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$lt4j")

    // Video/audio from websites (yt-dlp + ffmpeg).
    // 0.17.4 on purpose: ffmpeg 0.18.1 ships without libc++_shared.so and cannot run.
    val ytdl = "0.17.4"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")
}
