import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.carens.dashboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.carens.dashboard"
        minSdk = 26
        targetSdk = 36
        // CI sets BUILD_NUMBER to the Actions run number, which is also the release tag (build-N).
        val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
        buildConfigField("String", "UPDATE_REPO", "\"vishnuszipstack/carens-dashboard\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One fixed key for every build, so in-app updates install over the previous version.
    signingConfigs {
        create("carens") {
            storeFile = rootProject.file("signing/carens.jks")
            storePassword = "carens-dashboard"
            keyAlias = "carens"
            keyPassword = "carens-dashboard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("carens")
        }
        release {
            signingConfig = signingConfigs.getByName("carens")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.07.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("androidx.car.app:app:1.7.0")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for unit tests (the Android one is a stub on the JVM).
    testImplementation("org.json:json:20240303")
}
