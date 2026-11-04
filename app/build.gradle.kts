plugins {
    id("com.android.application")
}

android {
    namespace = "com.lyricwidget.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lyricwidget.app"
        // Android 8.0 (Oreo) en adelante.
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = "1.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Firmado con la clave de debug: la app se instala por USB o desde Releases, no va a la Play Store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
