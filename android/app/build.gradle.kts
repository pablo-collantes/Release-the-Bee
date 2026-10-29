plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.pablocollantes.releasethebee"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.pablocollantes.releasethebee"
        minSdk = 33          // Android 13: new BLE permission model + value-carrying GATT callbacks
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
    }

    signingConfigs {
        // A stable, project-local debug key keeps app data across reinstalls. It is
        // gitignored: in a public repo a shared key would let anyone ship an "update"
        // that inherits your installed app's data (including the seal token).
        // Create it once with the keytool command in README.md; without it the
        // machine's default debug key is used.
        val local = rootProject.file("debug.keystore")
        if (local.exists()) {
            getByName("debug") {
                storeFile = local
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        getByName("test") { java.srcDirs("src/test/kotlin") }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // WebSocket + HTTP client
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")           // Android's org.json is a stub in JVM tests
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
