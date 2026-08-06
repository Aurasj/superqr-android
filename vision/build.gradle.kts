plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.superqr.android.vision"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.open.cv)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
