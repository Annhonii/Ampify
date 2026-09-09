android {
    namespace = "com.example.batteryrestrict"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.android.ampify"
        minSdk = 26
        targetSdk = 34
        versionCode = 69
        versionName = "6.9"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "Ampify.apk"
        }
    }
}
