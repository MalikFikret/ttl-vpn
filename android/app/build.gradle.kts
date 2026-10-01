import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The keystore and its passwords never live in the repo. They come either from
// the environment variables TTLVPN_KEYSTORE, TTLVPN_KEYSTORE_PASSWORD, TTLVPN_KEY_ALIAS and
// (optional, defaults to the store password) TTLVPN_KEY_PASSWORD, or from a properties file
// outside the repo whose path is in TTLVPN_KEYSTORE_PROPERTIES (keys: storeFile, storePassword,
// keyAlias, keyPassword; see android/keystore.properties.example). Setting both is an error.
// Missing or invalid values only fail release builds, with a message that names the problem but
// never prints a value or path; debug builds don't need them. There is no fallback to the debug key.
class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String)

val repoDir: File = rootProject.projectDir.parentFile.canonicalFile
fun isInsideRepo(file: File): Boolean = file.canonicalFile.toPath().startsWith(repoDir.toPath())

var releaseSigning: ReleaseSigning? = null
val releaseSigningError: String? = run {
    fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }

    val signingVars = listOf("TTLVPN_KEYSTORE", "TTLVPN_KEYSTORE_PASSWORD", "TTLVPN_KEY_ALIAS", "TTLVPN_KEY_PASSWORD")
    val propertiesPath = env("TTLVPN_KEYSTORE_PROPERTIES")
    val values: Map<String, String?>
    val source: String
    var baseDir: File? = null
    if (propertiesPath != null) {
        if (signingVars.any { env(it) != null }) {
            return@run "Both TTLVPN_KEYSTORE_PROPERTIES and TTLVPN_KEY* environment variables are set. Use one or the other."
        }
        val propertiesFile = file(propertiesPath)
        if (!propertiesFile.isFile) return@run "The file named by TTLVPN_KEYSTORE_PROPERTIES does not exist."
        if (isInsideRepo(propertiesFile)) {
            return@run "The file named by TTLVPN_KEYSTORE_PROPERTIES is inside the repository. Move it outside the repo."
        }
        val text = providers.fileContents(objects.fileProperty().fileValue(propertiesFile)).asText.get()
        val properties = Properties().apply { load(StringReader(text)) }
        fun prop(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
        values = mapOf(
            "storeFile" to prop("storeFile"),
            "storePassword" to prop("storePassword"),
            "keyAlias" to prop("keyAlias"),
            "keyPassword" to prop("keyPassword"),
        )
        source = "the TTLVPN_KEYSTORE_PROPERTIES file"
        baseDir = propertiesFile.parentFile
    } else {
        values = mapOf(
            "storeFile" to env("TTLVPN_KEYSTORE"),
            "storePassword" to env("TTLVPN_KEYSTORE_PASSWORD"),
            "keyAlias" to env("TTLVPN_KEY_ALIAS"),
            "keyPassword" to env("TTLVPN_KEY_PASSWORD"),
        )
        source = "the TTLVPN_KEYSTORE, TTLVPN_KEYSTORE_PASSWORD and TTLVPN_KEY_ALIAS environment variables"
    }

    val missing = listOf("storeFile", "storePassword", "keyAlias").filter { values[it] == null }
    if (missing.isNotEmpty()) {
        return@run "Release signing is not configured: missing ${missing.joinToString()} in $source. " +
            "Set the TTLVPN_KEYSTORE* environment variables, or TTLVPN_KEYSTORE_PROPERTIES to a properties " +
            "file outside the repo. See android/keystore.properties.example."
    }
    // A relative storeFile in the properties file is relative to that file, not to the build.
    val storeFile = File(values.getValue("storeFile")!!).let { if (it.isAbsolute || baseDir == null) it else File(baseDir, it.path) }
    if (!storeFile.isFile) return@run "The release keystore from $source does not exist."
    if (isInsideRepo(storeFile)) return@run "The release keystore is inside the repository. Move it outside the repo."

    val storePassword = values.getValue("storePassword")!!
    releaseSigning = ReleaseSigning(
        storeFile = storeFile,
        storePassword = storePassword,
        keyAlias = values.getValue("keyAlias")!!,
        // PKCS12 keystores (keytool's default) use a single password for store and key.
        keyPassword = values["keyPassword"] ?: storePassword,
    )
    null
}

android {
    namespace = "com.malikfikret.ttlvpn"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.malikfikret.ttlvpn"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = signing.storeFile
                storePassword = signing.storePassword
                keyAlias = signing.keyAlias
                keyPassword = signing.keyPassword
            }
        }
    }

    buildTypes {
        // Installs next to the release build (own package, "(debug)" labels from src/debug/res),
        // so testing a debug build never means uninstalling the release one and losing its settings.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 code shrinking and resource shrinking. Keep rules: src/main/keepRules/rules.keep.
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME for the About section (off by default since AGP 8).
        buildConfig = true
    }
    // The in-app language picker needs every language in every install. With language
    // splits, an App Bundle would only ship the phone's languages, so picking another
    // one would silently fall back to English. (No effect on plain APK builds.)
    bundle {
        language {
            enableSplit = false
        }
    }
}

// Fails release builds (assembleRelease, bundleRelease, installRelease...) before any work when
// signing isn't configured. Only the message is captured, so no credential reaches the task.
val checkReleaseSigning = tasks.register("checkReleaseSigning") {
    val error = releaseSigningError
    doLast {
        if (error != null) throw GradleException(error)
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseSigning)
}

dependencies {
    // Go engine built by gomobile (build it first: see engine/)
    implementation(files("../../engine/build/ttlvpn.aar"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
