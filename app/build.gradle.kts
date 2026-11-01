plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "cl.apophis.simulator"; compileSdk = 35
 defaultConfig { applicationId = "cl.apophis.simulator"; minSdk = 26; targetSdk = 35; versionCode = 11; versionName = "0.9.1" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
