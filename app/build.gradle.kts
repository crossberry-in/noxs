plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val noxsVersionName = providers.gradleProperty("noxsVersionName").orElse("0.4.0").get()
val noxsVersionCode = providers.gradleProperty("noxsVersionCode").orElse("4000").get().toInt()

android {
    namespace = "com.crossberry.noxs"
    compileSdk = 34
    // Pinned: AGP's default (26.1) triggers SDK auto-install on machines that
    // only have 26.3 provisioned (CI installs 26.3 explicitly).
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.crossberry.noxs"
        minSdk = 24
        targetSdk = 34
        versionCode = noxsVersionCode
        versionName = noxsVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // ARM64 priority; x86_64 for emulator testing.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // Release signing via environment variables (CI). Falls back to debug
        // identity so `assembleRelease` always produces an artifact.
        create("release") {
            val ks = System.getenv("NOXS_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("NOXS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOXS_KEY_ALIAS")
                keyPassword = System.getenv("NOXS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val ks = System.getenv("NOXS_KEYSTORE_FILE")
            signingConfig = if (ks != null && file(ks).exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        // REQUIRED: proot + deps must be EXTRACTED to nativeLibraryDir so the
        // app can execve() them (Android 10+ denies exec from app data dirs,
        // and uncompressed in-APK libs are not extracted by default on 23+).
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":noxs-shared"))
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Editor: the workspace is the real code-server (VS Code Server) rendered
    // in a WebView — no native editing engine is bundled. code-server itself
    // is downloaded at install time into the guest (MIT; see NOTICE.md).

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
