plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.bustramnantes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bustramnantes.app"
        minSdk = 26
        targetSdk = 35
        // Literal version so F-Droid can regex-read it and its plain `gradle assembleRelease`
        // (no -P properties passed) produces an APK whose versionCode/versionName match the
        // fdroiddata build block. BUMP BOTH on every release and tag the commit `v<versionName>`
        // (e.g. v1.0.0) - F-Droid's UpdateCheckMode: Tags relies on the tag + these literals.
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Reproducible builds (F-Droid): strip the dependency-metadata block Android would otherwise
    // embed in the APK/bundle. That block is non-deterministic and signed with a Google key, so it
    // breaks byte-for-byte reproducibility and prevents F-Droid from verifying our published binary
    // against their rebuild. See https://gitlab.com/fdroid/fdroiddata/-/issues/3330
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            // R8 full-mode: shrink + obfuscate + strip unused resources, to keep the APK small.
            // Keep rules live in proguard-rules.pro (kotlinx.serialization DTOs, MapLibre JNI, etc.).
            // No signingConfig on purpose: the release build is UNSIGNED, exactly what F-Droid
            // builds; fdroid/sign-release.sh signs it with apksigner for the GitHub release.
            isMinifyEnabled = true
            // Reproducible builds: keep the git commit/remote out of the APK
            // (META-INF/version-control-info.textproto), so a rebuild from a source tarball or a
            // differently-cloned tree is byte-identical.
            vcsInfo.include = false
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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

    androidResources {
        // The bundled line-raster seed is an MBTiles (SQLite of already-compressed PNGs); skip aapt
        // re-compression - it would cost CPU/APK size for no gain (it's copied out via a stream, not
        // mmap'd, so leaving it uncompressed has no downside).
        noCompress += "mbtiles"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Pure-JVM transit core: GTFS converter, packed network, RAPTOR, board composition.
    implementation(project(":transit"))

    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // DI - Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // NOTE: hilt-work is intentionally NOT included - the WorkManager workers and the Glance widget
    // obtain their dependencies via EntryPointAccessors (see worker/CacheRefreshHiltEntryPoint.kt,
    // widget/WidgetHiltEntryPoint.kt), not @HiltWorker, so the hilt-work runtime + its compiler are
    // dead weight.

    // Persistence - Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Persistence - DataStore (Preferences)
    implementation(libs.datastore.preferences)

    // Networking
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging.interceptor)

    // Serialization / coroutines / datetime
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // Map
    implementation(libs.maplibre.android.sdk)

    // Widget - Glance
    implementation(libs.glance)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // XmlPullParser implementation for JVM tests of the SIRI parser (Android provides it at runtime).
    testImplementation(libs.kxml2)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Reproducible builds (F-Droid): the ART baseline profile (assets/dexopt/baseline.prof[m]), merged
// from the libraries' profiles, is not byte-stable from one build to the next, which would make
// F-Droid's rebuild differ from the published APK. Without it the app loses the libraries'
// pre-compiled startup paths (Compose); everything else is unchanged.
// See https://f-droid.org/docs/Reproducible_Builds/
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}
