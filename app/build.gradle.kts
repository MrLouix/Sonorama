import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Clé d'envoi du Play Store, lue dans keystore.properties (non versionné, voir keystore.properties.example).
// Sans ce fichier, la version de publication reste non signée, comme l'attend F-Droid.
val keystoreFile: File = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.isFile) keystoreFile.inputStream().use(::load) }

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

    signingConfigs {
        if (keystoreFile.isFile) {
            create("upload") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("upload")
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
        // Le modèle vocal est un ZIP : le recompresser ne gagnerait rien.
        noCompress += listOf("ogg", "zip")
    }

    bundle {
        // La langue se choisit dans l'appli : toutes les langues restent dans le paquet.
        language {
            enableSplit = false
        }
    }

    splits {
        // Vosk embarque environ 10 Mo de code natif par processeur : un APK par processeur.
        // Un AAB est découpé par le Play Store lui-même, et AGP refuse les APK multiples en le fabriquant.
        abi {
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
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

// ---------------------------------------------------------------- modèle vocal

/** Modèle Vosk intégré à l'APK : téléchargé une fois, vérifié par son empreinte, gardé en cache. */
abstract class DownloadVoskModel : DefaultTask() {
    @get:Input abstract val url: Property<String>

    @get:Input abstract val sha256: Property<String>

    @get:Internal abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val name = url.get().substringAfterLast('/')
        val cached = cacheDir.get().file(name).asFile
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!cached.isFile || sha(cached) != sha256.get()) {
            cached.parentFile.mkdirs()
            val partial = File(cached.path + ".part")
            logger.lifecycle("Téléchargement du modèle vocal ${url.get()}")
            URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
            val actual = sha(partial)
            if (actual != sha256.get()) {
                partial.delete()
                throw GradleException("Empreinte inattendue pour $name : $actual")
            }
            partial.renameTo(cached)
        }
        val target = outputDir.get().dir("vosk").asFile
        target.deleteRecursively()
        target.mkdirs()
        cached.copyTo(File(target, name))
    }
}

val voskModel = tasks.register<DownloadVoskModel>("downloadVoskModel") {
    // Doit rester identique à SpeechModels.BUNDLED (app/src/main/kotlin/.../voice/SpeechModels.kt).
    url = "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"
    sha256 = "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad"
    cacheDir = layout.dir(provider { gradle.gradleUserHomeDir.resolve("caches/sonorama-vosk") })
    outputDir = layout.buildDirectory.dir("generated/vosk")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(voskModel, DownloadVoskModel::outputDir)
        // Un code de version distinct par processeur, comme l'attendent les magasins d'applis.
        val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val code = abiCodes[abi] ?: 0
            output.versionCode.set((android.defaultConfig.versionCode ?: 1) * 10 + code)
        }
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

    // Reconnaissance vocale hors ligne. JNA est pris en AAR, avec ses bibliothèques natives Android.
    implementation(libs.vosk.android) { exclude(group = "net.java.dev.jna") }
    implementation(libs.jna) { artifact { type = "aar" } }

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
