plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.seamlesstravelapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.seamlesstravelapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true  // ✅ CRITICAL - Fixes all R class errors
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/DEPENDENCIES",
                "META-INF/notice.txt",
                "META-INF/license.txt",
                "META-INF/dependencies.txt",
                "META-INF/LGPL2.1"
            )
        }
    }
}

dependencies {
    // ============================================
    // PASSPORT NFC READING - JMRTD
    // ============================================
    
    implementation("org.jmrtd:jmrtd:0.7.42")
    implementation("net.sf.scuba:scuba-sc-android:0.0.25")
    
    // Spongy Castle (corrected names for v1.58.0.0)
    implementation("com.madgag.spongycastle:core:1.58.0.0")
    implementation("com.madgag.spongycastle:prov:1.58.0.0")
    implementation("com.madgag.spongycastle:bcpkix-jdk15on:1.58.0.0")
    implementation("com.madgag.spongycastle:bcpg-jdk15on:1.58.0.0")
    
    // Image processing for passport photos (JPEG2000)
    implementation("com.github.mhshams:jnbis:1.1.0")

    // ============================================
    // ANDROID CORE (FIXES "Unresolved reference")
    // ============================================
    
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    
    // Fragment & Activity (FIXES Fragment errors)
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // ============================================
    // NAVIGATION (FIXES navigation errors)
    // ============================================
    
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")

    // ============================================
    // CAMERAX
    // ============================================
    
    val cameraxVersion = "1.3.3"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ============================================
    // GOOGLE ML KIT
    // ============================================
    
    implementation("com.google.mlkit:text-recognition:16.0.0")
    implementation("com.google.mlkit:face-detection:16.1.6")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // ============================================
    // ARCHITECTURE COMPONENTS
    // ============================================
    
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")

    // ============================================
    // IMAGE LOADING
    // ============================================
    
    implementation("io.coil-kt:coil:2.6.0")

    // ============================================
    // GOOGLE WALLET INTEGRATION
    // ============================================
    
    implementation("com.google.android.gms:play-services-pay:16.1.0")

    // ============================================
    // TESTING
    // ============================================
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}