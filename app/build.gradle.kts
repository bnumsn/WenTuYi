plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wentuyi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wentuyi.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 6
        versionName = "0.5.1"

        // androidx.test.runner.AndroidJUnitRunner lets `connectedDebugAndroidTest`
        // discover @Test methods. The legacy single-class Instrumentation is
        // retained as a debug entry point but isn't the default runner anymore.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf("-Xjvm-default=all")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // The app is Chinese-only today and builds most UI in Kotlin views, so
        // resource extraction would add churn without improving the current product.
        // Dependency versions are intentionally pinned to the Kotlin/AGP stack here.
        disable += setOf("SetTextI18n", "GradleDependency")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // QR Code encoding/decoding — pure Java, no AndroidX.
    implementation("com.google.zxing:core:3.5.3")

    // Argon2id KDF and X25519 — pure Java, no AndroidX.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // AndroidX test runners are intentionally limited to `androidTestImplementation`
    // so the shipped APK stays AndroidX-free. They give `connectedDebugAndroidTest`
    // proper JUnit @Test discovery instead of the v0.5 "0 tests run" report.
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("junit:junit:4.13.2")
}
