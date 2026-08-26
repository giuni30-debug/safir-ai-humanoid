plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.safir.ai.humanoid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.safir.ai.humanoid"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        buildConfigField("String", "SUPABASE_URL", "\"https://hsximwxfwkzbnjwlzfuy.supabase.co\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImhzeGltd3hmd2t6Ym5qd2x6ZnV5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODc2Njk0NzUsImV4cCI6MjEwMzI0NTQ3NX0.w7v3e07av9vUfabJR2f4n8xRbEk9BzjECl112-_YwCQ\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // HeyGen LiveAvatar returns a LiveKit room URL + participant token.
    // LiveKit is therefore the realtime video transport for the speaking avatar.
    implementation("io.livekit:livekit-android:2.28.0")

    // LITE mode exposes a WebSocket for agent.speak PCM audio commands.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
