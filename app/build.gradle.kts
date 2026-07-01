import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.protobuf") version "0.9.4"
}

// Load signing credentials from keystore.properties (never committed to git).
// Falls back gracefully so debug builds work on machines without the keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "app.sabre.wzsabre"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sabre.wzsabre"
        // minSdk 24: LcsSource uses Map.computeIfAbsent (API 24). Nothing runs
        // Highway Radar on Android 6.0 (API 23), so this drops no real users.
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file("${keystoreProps["storeFile"]}")
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // android.util.Log no-ops in JVM unit tests (LcsSource logs in its
        // feed-salvage path, which the truncation tests exercise)
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

// Generates Java from app/src/main/proto/*.proto using the protobuf-lite runtime.
// Used to emulate the Waze mobile-app RT protocol (register/login/alert query).
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}