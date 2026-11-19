plugins {
    id("com.android.application")
}

android {
    namespace = "vn.edu.mobileoptimization.debounce"
    compileSdk = 36

    defaultConfig {
        applicationId = "vn.edu.mobileoptimization.debounce"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
