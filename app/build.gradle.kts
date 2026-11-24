plugins {
    id("com.android.application")
}

val appVersionName = "0.2.3"
val appVersionCode = 6

base {
    archivesName.set("kapan-v$appVersionName")
}

android {
    namespace = "com.vanipy.kapan"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vanipy.kapan"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KAPAN_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KAPAN_STORE_PASSWORD")
                keyAlias = System.getenv("KAPAN_KEY_ALIAS")
                keyPassword = System.getenv("KAPAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("KAPAN_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
