plugins {
    id("com.android.application")
}

android {
    namespace = "com.ekranyansit.telefon"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ekranyansit.telefon"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Kişisel kullanım için: release sürümü de debug anahtarıyla imzalanır,
            // böylece APK doğrudan kurulabilir.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
