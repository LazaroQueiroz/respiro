plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.respiro"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.respiro"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("pt")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Assinado com a chave de debug para instalar direto no celular.
            signingConfig = signingConfigs.getByName("debug")
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
