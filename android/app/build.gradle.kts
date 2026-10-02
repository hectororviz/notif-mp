plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("com.google.devtools.ksp")
}

android {
  namespace = "ar.com.notifmp"
  compileSdk = 34
  defaultConfig {
    applicationId = "ar.com.notifmp"
    minSdk = 26
    targetSdk = 34
    versionCode = 2
    versionName = "1.1.0"
    buildConfigField("String", "MP_CLIENT_ID", "\"5631264729819538\"")
    buildConfigField("String", "REDIRECT_URI", "\"https://notif.mposw.com.ar/oauth/callback\"")
    buildConfigField("String", "PROXY_URL", "\"https://notif.mposw.com.ar\"")
    buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-9763480712544528/7201783224\"")
  }
  buildTypes {
    release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    debug { applicationIdSuffix = ".debug" }
  }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { compose = true; buildConfig = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
  val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
  implementation(composeBom); androidTestImplementation(composeBom)
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.security:security-crypto:1.1.0-alpha06")
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  ksp("androidx.room:room-compiler:2.6.1")
  implementation("androidx.browser:browser:1.8.0")
  implementation("com.google.android.gms:play-services-ads:23.0.0")
  implementation("com.android.billingclient:billing-ktx:7.0.0")
  implementation("com.google.android.ump:user-messaging-platform:2.2.0")
  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
  implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
  implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
  implementation("com.google.zxing:core:3.5.3")
  implementation("androidx.compose.foundation:foundation")
  implementation("com.airbnb.android:lottie-compose:6.4.0")
  implementation("org.apache.poi:poi-ooxml:5.2.5")
}
