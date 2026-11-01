plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from environment variables (set by CI from GitHub secrets).
val ksPath: String? = System.getenv("KEYSTORE_PATH")
val ksPass: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val keyPass: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: ksPass

android {
    namespace = "com.backrooms.game"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.backrooms.game"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        release {
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
