import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.hilt.android.gradle)
    id("io.objectbox") // Apply last
}

android {
    namespace = "com.ambientai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ambientai"
        minSdk = 31 // Gemini Nano requirement
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val properties = Properties()
        file("../local.properties").inputStream().use { properties.load(it) }
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", "\"${properties.getProperty("picovoice.accessKey", "")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${properties.getProperty("groq.apiKey", "")}\"")
        buildConfigField("String", "BRAVE_SEARCH_API_KEY", "\"${properties.getProperty("brave.searchApiKey", "")}\"")
    }

    buildTypes {
        release {
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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.5")
    implementation(libs.porcupine.android)
    implementation("androidx.media:media:1.7.1")

    // Flow support for Compose - NEW DEPENDENCY
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Existing LiveData support (can be removed later if fully migrated)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.lifecycle.livedata.ktx)

    // Hilt for dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}