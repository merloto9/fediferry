plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Version is supplied by CI from the git tag; these are the local-build fallbacks.
val appVersionName: String = (findProperty("fediferry.versionName") as String?) ?: "0.1.0"
val appVersionCode: Int = ((findProperty("fediferry.versionCode") as String?) ?: "1").toInt()

fun env(name: String): String =
    System.getenv(name) ?: (findProperty(name) as String?).orEmpty()

/** Non-null only when a real release keystore has been supplied. */
val releaseKeystore: java.io.File? =
    env("RELEASE_KEYSTORE_PATH").takeIf { it.isNotBlank() }
        ?.let { rootProject.file(it) }
        ?.takeIf { it.isFile }

android {
    namespace = "app.fediferry"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fediferry"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Consumed by the OAuth redirect intent filter in the manifest, and by
        // AuthManager when it builds the redirect URI. Debug gets its own scheme
        // so both builds can be installed side by side.
        manifestPlaceholders["oauthScheme"] = "fediferry"
        buildConfigField("String", "OAUTH_SCHEME", "\"fediferry\"")
    }

    signingConfigs {
        // A checked-in debug keystore. AGP otherwise generates a fresh one per
        // machine, which would give every CI run a different signature — and an
        // Obtainium update whose signature changed cannot be installed over the
        // previous version.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Used only when a real release key is supplied (locally via
        // ~/.gradle/gradle.properties, in CI via the RELEASE_KEYSTORE_* secrets).
        // Until then releases are signed with the pinned debug key above.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = env("RELEASE_STORE_PASSWORD")
                keyAlias = env("RELEASE_KEY_ALIAS")
                keyPassword = env("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["oauthScheme"] = "fediferry-debug"
            buildConfigField("String", "OAUTH_SCHEME", "\"fediferry-debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName(
                if (releaseKeystore != null) "release" else "debug",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("androidTest") {
        assets.srcDirs("$projectDir/schemas")
    }

    lint {
        // minSdk is already 26, so lint calls the `-v26` qualifier redundant —
        // but aapt2 does not resolve the adaptive icon from a bare
        // `mipmap-anydpi` folder, so the qualifier stays.
        disable += "ObsoleteSdkInt"
        warningsAsErrors = false
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.sharetarget)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Android's regex engine is ICU, the host JVM's is not. Anything that
    // depends on that difference can only be caught on a device.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
