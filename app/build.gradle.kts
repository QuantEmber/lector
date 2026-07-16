import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is driven by a gitignored keystore.properties (see
// 05_release/signing/). Absent it (e.g. a fresh clone of the public repo), the
// release build simply goes unsigned — the project still compiles for anyone.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.lector"
    compileSdk = 35

    // AGP embeds a Google Play dependency-reporting blob in the APK signing block by
    // default. F-Droid's `check apk` scanner rejects any extra signing block, and it is
    // Play-oriented metadata a FOSS build has no use for. Off in both outputs.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        // L-GATE-2 (2026-07-05): F-Droid-safe id derived from quantember.github.io
        // (Brian's domain). Code namespace stays app.lector. Permanent once published.
        applicationId = "io.github.quantember.lector"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Required for F-Droid reproducible builds: AGP otherwise embeds
            // META-INF/version-control-info.textproto, whose contents depend on the
            // build environment's git checkout. A build made outside a git tree writes
            // NO_SUPPORTED_VCS_FOUND while F-Droid's writes the commit hash, so the
            // APKs can never match. Omitting the file entirely makes both sides equal.
            vcsInfo { include = false }
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// FOSS-clean constraint (ADR-003): no Play Services, no Firebase, no billing,
// no analytics — in ANY flavor. Additions here must pass F-Droid inclusion.
dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.pdfbox.android) // PDF text extraction (Apache-2.0, FOSS-clean)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
