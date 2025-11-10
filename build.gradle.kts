buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:5.0.1")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt.android.gradle) apply false
}