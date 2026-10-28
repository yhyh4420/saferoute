plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

// The project lives on an iCloud-synced Desktop, which drops "name 2" copies into build
// outputs and breaks Gradle's hashing; keep build outputs outside the synced folder.
layout.buildDirectory.set(file("${System.getProperty("user.home")}/.saferoute-build/app"))

// Meta Model API key for the planning agent, read from the repo-root .env (never committed).
val metaApiKey: String = rootDir.resolve("../.env").takeIf { it.exists() }?.readLines()
    ?.firstOrNull { it.startsWith("META_API_KEY=") }?.substringAfter("=")?.trim().orEmpty()

android {
    namespace = "com.saferoute.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.saferoute.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "META_API_KEY", "\"$metaApiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Packs are memory-mapped from assets; keep them uncompressed in the APK.
        noCompress += listOf("bin", "sqlite")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    // API 37 removed InputManager.getInstance(), which espresso 3.7 still calls via reflection.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha01")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
