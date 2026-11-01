plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "com.snapclean.app"
 compileSdk = 35
 defaultConfig { applicationId = "com.snapclean.app"; minSdk = 26; targetSdk = 35; versionCode = 12; versionName = "2.9.1-beta" }
 signingConfigs {
  create("stableRelease") {
   val path = System.getenv("SNAPCLEAN_KEYSTORE_PATH")
   if (!path.isNullOrBlank()) {
    storeFile = file(path)
    storePassword = System.getenv("SNAPCLEAN_STORE_PASSWORD")
    keyAlias = System.getenv("SNAPCLEAN_KEY_ALIAS")
    keyPassword = System.getenv("SNAPCLEAN_KEY_PASSWORD")
   }
  }
 }
 buildTypes {
  getByName("release") {
   if (!System.getenv("SNAPCLEAN_KEYSTORE_PATH").isNullOrBlank()) {
    signingConfig = signingConfigs.getByName("stableRelease")
   }
   isMinifyEnabled = false
  }
 }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.compose.ui:ui")
 implementation("org.jsoup:jsoup:1.18.3")
 testImplementation("junit:junit:4.13.2")
}