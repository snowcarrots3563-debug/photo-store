import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}
val currentUpdate = (JsonSlurper().parse(file("src/main/assets/updates.json")) as List<*>).first() as Map<*, *>

android {
    namespace = "dev.local.artistgallery"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    testBuildType = "phase2Test"
    defaultConfig {
        applicationId = "dev.local.artistgallery"
        minSdk = 26
        targetSdk = 35
        versionCode = (currentUpdate["versionCode"] as Number).toInt()
        versionName = currentUpdate["versionName"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testApplicationId = "dev.local.artistgallery.phase2test.phase4test"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        create("phase2Test") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".phase2test"
            matchingFallbacks += listOf("debug")
        }
        release { isMinifyEnabled = false }
    }
    buildFeatures { compose = true }
    sourceSets["phase2Test"].java.srcDir("src/debug/java")
    sourceSets["phase2Test"].manifest.srcFile("src/debug/AndroidManifest.xml")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(libs.core)
    implementation(libs.activity)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.savedstate)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.zip4j)
    implementation(files("libs/sevenzip-16.02-2.03.aar"))
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.coroutines.test)
    add("phase2TestImplementation", libs.compose.testmanifest)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.testmanifest)
}
