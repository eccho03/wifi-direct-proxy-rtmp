import java.util.Properties
import kotlin.apply

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
}

repositories {
    google()
    mavenCentral()
    flatDir {
        dirs("libs")
    }
}

android {
    namespace = "com.example.wifidirectproxysocks"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProperties = Properties().apply {
            load(rootProject.file("local.properties").inputStream())
        }

        val streamKey = localProperties.getProperty("STREAMING_KEY") ?: throw GradleException("Streaming Key가 local.properties에 없습니다")
        buildConfigField("String", "STREAMING_KEY", streamKey)
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
    kotlinOptions {
        jvmTarget = "11"
    }
    viewBinding {
        enable = true
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS@aar")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

}
