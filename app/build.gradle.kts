import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
/** GitHub repository ("owner/name") whose releases the app updates itself from. */
val updateRepo = providers.gradleProperty("updateRepo").getOrElse("")

android {
    namespace = "com.gjnet.shuffleframe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gjnet.shuffleframe"
        minSdk = 29
        targetSdk = 35
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One fixed key for every build: Android only accepts updates signed with the same key.
        // It lives outside the repo; back it up (losing it means reinstalling from scratch).
        create("frame") {
            storeFile = file(System.getProperty("user.home") + "/Android/keys/shuffleframe.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("frame")
        }
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: keep `adb shell run-as` working so the tablet's data can be inspected.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("frame")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
