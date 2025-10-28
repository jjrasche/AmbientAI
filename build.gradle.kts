buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:4.0.3")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}