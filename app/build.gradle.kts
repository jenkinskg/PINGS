plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace="com.jenkinskg.pings"
    compileSdk=35
    defaultConfig { applicationId="com.jenkinskg.pings"; minSdk=26; targetSdk=35; versionCode=14; versionName="0.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }

dependencies { implementation("com.github.mwiede:jsch:0.2.21") }
