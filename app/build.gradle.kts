import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ru.timegrip.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.timegrip.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 46
        versionName = "1.4.24"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key lives outside the repo: the keystore path and password
    // come from ~/.gradle/gradle.properties (timegrip.signing.*). Every release
    // APK must be signed with it, or it will not install over the previous one.
    val releaseStoreFile = providers.gradleProperty("timegrip.signing.storeFile").orNull
    if (releaseStoreFile != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = providers.gradleProperty("timegrip.signing.password").get()
            keyAlias = providers.gradleProperty("timegrip.signing.keyAlias").get()
            keyPassword = storePassword
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField(
                "String",
                "DEFAULT_API_URL",
                "\"${providers.gradleProperty("timegrip.apiUrl.debug").get()}\"",
            )
            // The local development backend (see gradle.properties) serves plain HTTP.
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            buildConfigField(
                "String",
                "DEFAULT_API_URL",
                "\"${providers.gradleProperty("timegrip.apiUrl.release").get()}\"",
            )
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            // Without the key the APK comes out unsigned and will not install,
            // rather than silently signed with a key that breaks updates.
            signingConfig = signingConfigs.findByName("release")
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

    androidResources {
        generateLocaleConfig = true
    }
}

androidComponents {
    // AGP has no public API for the APK file name; VariantOutputImpl is internal.
    // Same "TimeGrip_v<version>[-debug].apk" scheme for every build type, so a
    // debug build downloaded next to a release one is still easy to tell apart.
    onVariants { variant ->
        val suffix = if (variant.buildType == "debug") "-debug" else ""
        variant.outputs.forEach { output ->
            (output as VariantOutputImpl).outputFileName.set(output.versionName.map { "TimeGrip_v$it$suffix.apk" })
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
