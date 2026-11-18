plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing values come from environment variables (set by CI from GitHub secrets).
val ksPath: String? = System.getenv("KEYSTORE_PATH")
val ksPass: String? = System.getenv("KEYSTORE_PASSWORD")
val ksAlias: String? = System.getenv("KEY_ALIAS")
val keyPass: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: ksPass

// The workflow passes -PappVersionCode=<run number> -PappVersionName=1.0.<run number>, the same number as the release tag (the in-game update check compares them).
val vCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val vName = (project.findProperty("appVersionName") as String?) ?: "1.0.0-dev"

android {
    namespace = "com.backrooms.game"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.backrooms.game"
        minSdk = 21
        targetSdk = 34
        versionCode = vCode
        versionName = vName
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
    // Vulkan backend (C++). libvulkan is loaded at run time, so minSdk stays 21 and a device without Vulkan just falls back.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
