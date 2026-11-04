import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Versão vem da última tag do git (v1.2.3 → versionName "1.2.3", versionCode 10203).
// Assim, publicar uma versão é só criar a tag; sem tag, vale 1.0.0.
val versionTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().removePrefix("v")
val appVersion = versionTag.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "1.0.0"
val (major, minor, patch) = appVersion.split(".").map { it.toInt() }

// Chave de release: no computador vem de keystore.properties (fora do git);
// no GitHub Actions, das variáveis de ambiente preenchidas pelos secrets.
val keystore = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, env: String): String? =
    (keystore.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }
val releaseStore = secret("storeFile", "RESPIRO_KEYSTORE")

android {
    namespace = "app.respiro"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.respiro"
        minSdk = 30 // Android 11+
        targetSdk = 35
        versionCode = major * 10000 + minor * 100 + patch
        versionName = appVersion
        resourceConfigurations += listOf("pt")
    }

    signingConfigs {
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = secret("storePassword", "RESPIRO_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "RESPIRO_KEY_ALIAS")
                keyPassword = secret("keyPassword", "RESPIRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Sem a chave de release (ex.: CI de pull requests), assina com a de debug só para compilar.
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { resources { excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin") } }
}
// Sem dependências: só o framework Android + stdlib do Kotlin (encolhida pelo R8).
