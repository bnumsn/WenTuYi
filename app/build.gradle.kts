import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Read outside the android {} block: in there `java` resolves to Gradle's java extension,
// not the JDK package.
private val keystoreProps = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

private fun keystoreProp(name: String, env: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(env)

private val releaseStore: String? = keystoreProp("storeFile", "WENTUYI_KEYSTORE")

android {
    namespace = "com.wentuyi.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wentuyi.app"
        minSdk = 23
        targetSdk = 36
        versionCode = 12
        versionName = "0.7.1"

        // androidx.test.runner.AndroidJUnitRunner lets `connectedDebugAndroidTest`
        // discover @Test methods. The legacy single-class Instrumentation is
        // retained as a debug entry point but isn't the default runner anymore.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Ship the repo-level canonical vectors inside the test APK so VectorContractTest can
    // decode them with the app's own codec copy. Single source of truth with :shared-protocol.
    sourceSets {
        getByName("androidTest") {
            assets.srcDir(rootProject.file("protocol-fixtures"))
        }
    }

    /**
     * Release signing.
     *
     * A real key comes from `keystore.properties` next to this file (git-ignored) or from
     * the matching env vars, which is what CI uses via repository secrets. When neither is
     * present the build falls back to the debug key so `assembleRelease` still produces an
     * *installable* APK — otherwise the R8-minified build, the one that can actually break
     * (BouncyCastle and ZXing are reached reflectively), could never be run and tested.
     * A debug-signed APK is fine for testing and useless for distribution; the release
     * workflow labels it as such.
     */
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = keystoreProp("storePassword", "WENTUYI_KEYSTORE_PASSWORD")
                keyAlias = keystoreProp("keyAlias", "WENTUYI_KEY_ALIAS")
                keyPassword = keystoreProp("keyPassword", "WENTUYI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseStore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    // Portable JVM protocol core — the single source of the WTY codec / Double Ratchet.
    // Pure JDK + BouncyCastle, no Android APIs and no java.util.Base64 floor (safe at minSdk
    // 23). The app's old duplicate CryptoUtils/SecurePayloadCodec have been removed; the
    // remaining app classes are thin Android glue (Base64/JSON/Identity boundary) over this.
    implementation(project(":shared-protocol"))

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
