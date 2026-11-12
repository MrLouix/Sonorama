plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.mrlouix.sonorama"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.mrlouix.sonorama"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        androidResources.localeFilters += listOf("fr", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        // Les boucles Ogg sont déjà compressées : les stocker telles quelles permet la lecture directe.
        noCompress += "ogg"
    }

    bundle {
        // La langue se choisit dans l'appli : toutes les langues restent dans le paquet.
        language {
            enableSplit = false
        }
    }

    dependenciesInfo {
        // Exigence F-Droid : pas de bloc de métadonnées chiffré par Google dans l'APK.
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            // Les tests d'écran (Robolectric) ont besoin des ressources et des assets.
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2")
                // Les captures sont toujours écrites (app/build/screens) ; aucune comparaison à une référence.
                it.systemProperty("roborazzi.test.record", "true")
            }
        }
    }

    lint {
        warningsAsErrors = false
        // Les chaînes « %d sur %d » ne sont pas des pluriels.
        disable += "PluralsCandidate"
        abortOnError = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
