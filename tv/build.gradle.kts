plugins {
    id("com.android.application")
}

android {
    namespace = "com.ekranyansit.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ekranyansit.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
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
