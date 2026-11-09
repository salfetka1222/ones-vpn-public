plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 signingConfigs {
  create("onesRelease") {
   val store = System.getenv("ONES_SIGNING_STORE")
   if (!store.isNullOrBlank()) {
    storeFile = file(store)
    storePassword = System.getenv("ONES_SIGNING_PASSWORD")
    keyAlias = "ones-vpn"
    keyPassword = System.getenv("ONES_SIGNING_PASSWORD")
   }
  }
 }
 namespace = "app.onesvpn"
 compileSdk = 35
 defaultConfig {
  applicationId = "app.onesvpn"
  minSdk = 29
  ndk { abiFilters += "arm64-v8a" }
  targetSdk = 35
  versionCode = 38
  versionName = "1.0.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 buildTypes {
  getByName("release") {
   signingConfig = signingConfigs.getByName("onesRelease")
   isMinifyEnabled = false
  }
 }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
 implementation(files("libs/libbox.aar"))
 implementation("androidx.core:core-ktx:1.16.0")
 implementation(platform("androidx.compose:compose-bom:2025.04.01"))
 implementation("androidx.activity:activity-compose:1.10.1")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.foundation:foundation")
}
