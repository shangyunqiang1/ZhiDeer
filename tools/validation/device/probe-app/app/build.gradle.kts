plugins {
    id("com.android.application")
}

android {
    namespace = "com.zhideer.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zhideer.probe"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
