plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services") // ✅ aplicar sin versión aquí
}

android {
    namespace = "com.example.panico"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.panico"
        minSdk = 33
        targetSdk = 36
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.firebase.auth)

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // 🔥 Firebase (BOM + lo que realmente uses)
    implementation(platform("com.google.firebase:firebase-bom:34.2.0"))
    implementation("com.google.firebase:firebase-analytics") // opcional

    // 👇 Añade estas si importas Auth/RTDB (tu MainActivity las usa)
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
}
