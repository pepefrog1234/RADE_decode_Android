import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Keep credentials out of source control. CI environment values take precedence.
val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}
fun releaseSigningValue(property: String, environment: String): String? =
    providers.environmentVariable(environment).orNull
        ?: releaseSigningProperties.getProperty(property)

val releaseStorePath = releaseSigningValue("storeFile", "RADE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningValue("storePassword", "RADE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "RADE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "RADE_RELEASE_KEY_PASSWORD")
val releaseStoreFile = releaseStorePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
val releaseCertificateFingerprintFile = rootProject.file("docs/signing/release-certificate.sha256")
val missingReleaseSigningSettings = mapOf(
    "storeFile / RADE_RELEASE_STORE_FILE" to releaseStorePath,
    "storePassword / RADE_RELEASE_STORE_PASSWORD" to releaseStorePassword,
    "keyAlias / RADE_RELEASE_KEY_ALIAS" to releaseKeyAlias,
    "keyPassword / RADE_RELEASE_KEY_PASSWORD" to releaseKeyPassword
).filterValues { it.isNullOrBlank() }.keys.toList()

val validateReleaseSigningConfiguration = tasks.register("validateReleaseSigningConfiguration") {
    group = "verification"
    description = "Require a persistent signing key before any release build."
    doLast {
        check(missingReleaseSigningSettings.isEmpty()) {
            "Release signing is not configured: ${missingReleaseSigningSettings.joinToString()}. " +
                "Set keystore.properties or the RADE_RELEASE_* environment variables. " +
                "See docs/signing/README.zh-TW.md. Unsigned releases are disabled."
        }
        check(releaseStoreFile?.isFile == true) {
            "Release keystore does not exist. Restore the existing key; do not generate a replacement. " +
                "See docs/signing/README.zh-TW.md."
        }
        val keyStore = KeyStore.getInstance(releaseStoreFile, releaseStorePassword!!.toCharArray())
        val certificate = checkNotNull(keyStore.getCertificate(releaseKeyAlias)) {
            "Release key alias was not found in the keystore."
        }
        val actualFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02X".format(it) }
        val expectedFingerprint = releaseCertificateFingerprintFile.readText()
            .trim().replace(":", "").uppercase()
        check(actualFingerprint == expectedFingerprint) {
            "Release signing certificate does not match docs/signing/release-certificate.sha256. " +
                "Restore the correct keystore to preserve update compatibility."
        }
    }
}

// Attach to the task dependency graph, so aggregate/abbreviated builds are covered.
tasks.configureEach {
    if (name == "preReleaseBuild" || name == "validateSigningRelease") {
        dependsOn(validateReleaseSigningConfiguration)
    }
}

android {
    namespace = "yakumo2683.RADEdecode"
    compileSdk = 35

    defaultConfig {
        applicationId = "yakumo2683.RADEdecode"
        minSdk = 26
        targetSdk = 35
        versionCode = 10625
        versionName = "1.6.25-icom-cat-recovery"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = false // minSdk 26 supports APK Signature Scheme v2.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // Version banner in connection logs (BuildConfig.VERSION_NAME).
        buildConfig = true
    }

    testOptions {
        // Protocol integration tests use real UDP and a fake PTY; only Android
        // logging is stubbed by the local JVM test runtime.
        unitTests.isReturnDefaultValues = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Lifecycle Service
    implementation(libs.lifecycle.service)

    // Network
    implementation(libs.okhttp)

    // Location & Map
    implementation(libs.play.services.location)
    implementation(libs.osmdroid)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
