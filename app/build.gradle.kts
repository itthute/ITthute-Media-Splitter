plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "africa.itthute.mediasplitter"
    compileSdk = 35

    defaultConfig {
        applicationId = "africa.itthute.mediasplitter"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.7")

    // ffmpeg-kit-full 8.1.7 currently publishes an AAR without declaring these
    // required runtime helper dependencies in its Maven POM. Declare them
    // explicitly so FFmpegKitConfig can initialise on a physical Android device.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("com.arthenica:smart-exception-common:0.2.1")
}
