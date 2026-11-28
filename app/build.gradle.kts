plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wykasolutions.gmailsender"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wykasolutions.gmailsender"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // Google ties the Gmail permission to the signing key's SHA-1, so this app must
    // always be signed with the same key. The key is supplied by CI secrets or by
    // local environment variables; see README.md.
    signingConfigs {
        create("owner") {
            val store = System.getenv("KEYSTORE_FILE")
            if (!store.isNullOrBlank() && file(store).exists()) {
                storeFile = file(store)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingConfigs.getByName("owner").storeFile != null) {
                signingConfig = signingConfigs.getByName("owner")
            }
        }
        debug {
            if (signingConfigs.getByName("owner").storeFile != null) {
                signingConfig = signingConfigs.getByName("owner")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
