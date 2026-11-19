plugins { id("com.android.application") }

android {
    namespace = "vn.edu.mobileoptimization.securestore"
    compileSdk = 36
    defaultConfig {
        applicationId = "vn.edu.mobileoptimization.securestore"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes.getByName("debug") {
        if (providers.gradleProperty("app6Validation").orNull == "true") applicationIdSuffix = ".validation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
