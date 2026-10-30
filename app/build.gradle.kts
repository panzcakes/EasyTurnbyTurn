plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.clusternav"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.clusternav.test"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.2"
    }

    // Signing: the GitHub build passes a key through environment variables (see README). Without
    // them the normal debug key is used, so the project also builds on a fresh checkout.
    val keystorePath: String? = System.getenv("KEYSTORE_FILE")
    if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
