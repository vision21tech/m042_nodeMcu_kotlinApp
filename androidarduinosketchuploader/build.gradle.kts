plugins {
    id("com.android.library")
}



android {
    namespace = "com.example.androidarduinosketchuploader"
    compileSdk = 34


    defaultConfig {
        minSdk = 24
        targetSdk = 34

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
                proguardFiles (
                    getDefaultProguardFile("proguard-android-optimize.txt") ,
                    "proguard-rules.pro"
                )
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(fileTree(mapOf("include" to listOf("*.jar"), "dir" to "libs")))

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("com.android.support.test:runner:1.0.2")
    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2")
    implementation(project(":IntelHexFormatReader"))

}
