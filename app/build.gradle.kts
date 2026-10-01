plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="com.jenkinskg.pings"; compileSdk=35
defaultConfig { applicationId="com.jenkinskg.pings"; minSdk=26; targetSdk=35; versionCode=3; versionName="0.3" } }