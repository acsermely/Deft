plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.deft"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.deft"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // PARITY G10: account type and sync authority follow the applicationId (debug has its own).
        resValue("string", "fl_account_type", "app.deft.account")
        resValue("string", "fl_sync_authority", "app.deft.sync")
    }

    buildTypes {
        release {
            // R8 drops unused code (most of material-icons-extended) and unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "fl_account_type", "app.deft.debug.account")
            resValue("string", "fl_sync_authority", "app.deft.debug.sync")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,NOTICE.md,LICENSE.md,NOTICE.txt,LICENSE.txt}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.14.0")
    // W5: isolate handlers of agent tools (no bridge, no network, heap limit).
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    // PARITY E3: Custom Tab for sign-in pages.
    implementation("androidx.browser:browser:1.8.0")
    // PARITY D2: `periodic:N` background triggers. 2.11+ pulls Room 2.7 and Kotlin 2.1 stdlib.
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // PARITY D4: MediaSessionCompat and MediaStyle notifications. 1.8.0 would pull kotlin-stdlib 2.1.
    implementation("androidx.media:media:1.7.1")
    // PARITY D6: UnifiedPush (push without Google). 3.x pulls Tink 1.17 (clashes with
    // security-crypto's tink-android) and 3.1+ Kotlin 2.2+ stdlib; 2.5.0 depends on kotlin-stdlib only.
    implementation("org.unifiedpush.android:connector:2.5.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // biometric pulls fragment 1.2.5; activity-result APIs need 1.3+ (lint InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.0.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    testImplementation("junit:junit:4.13.2")
}

val copyDeftBridge = tasks.register<Copy>("copyDeftBridge") {
    from(rootProject.projectDir.resolve("../samples/deft-bridge.js"))
    into(layout.projectDirectory.dir("src/main/assets/bridge"))
}
val copyBundledApps = tasks.register<Copy>("copyBundledApps") {
    from(rootProject.projectDir.resolve("../samples")) {
        exclude("catalog.json")
        exclude("deft-bridge.js")
        exclude("hostile/**")
        exclude("**/.*")
    }
    into(layout.projectDirectory.dir("src/main/assets/deft-apps"))
}
tasks.named("preBuild").configure {
    dependsOn(copyDeftBridge)
    dependsOn(copyBundledApps)
}
