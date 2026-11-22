import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The cloud build passes -PminifyEnabled=true (small APK) and falls back to false if shrinking ever fails.
val minify = (project.findProperty("minifyEnabled") as String?)?.toBoolean() ?: true

android {
    namespace = "com.example.audioonly"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.audioonly"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Personal-use app: sign with the auto-generated debug key so the APK is installable right away.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

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
    // Audio player + background playback + notification / lock-screen controls
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    // Finds the real audio stream behind YouTube / SoundCloud / Bandcamp / PeerTube links.
    // Sites change often: if a site stops working, bump this to the newest release from
    // https://github.com/TeamNewPipe/NewPipeExtractor/releases
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
