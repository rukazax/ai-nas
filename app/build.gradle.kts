plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ru.ainas.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.ai.nas"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
        vectorDrawables { useSupportLibrary = true }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/LICENSE.md"
        resources.excludes += "META-INF/LICENSE.txt"
        resources.excludes += "META-INF/NOTICE.md"
        resources.excludes += "META-INF/NOTICE.txt"
        resources.excludes += "META-INF/ASL2.0"
        resources.excludes += "META-INF/LGPL2.1"
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }
}

configurations.configureEach {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
    exclude(group = "org.bouncycastle", module = "bcpkix-jdk15on")
}


dependencies {
    // --- Jetpack Compose (единый BOM) ---
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // --- AndroidX ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.browser:browser:1.8.0") // Chrome Custom Tabs для DSH-pair

    // --- Kotlin ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // --- Сеть ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- Протоколы хранилищ ---
    implementation("org.codelibs:jcifs:2.1.7") // jcifs-ng: SMB2/3 (официальная координата Maven Central)
    implementation("com.github.lookfirst:sardine:5.10") // WebDAV
    implementation("com.hierynomus:sshj:0.38.0") // SSSH/SFTP
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") // полный BC: X25519 и др. для sshj
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1") // провайдер BC для sshj

    // --- Картинки ---
    implementation("io.coil-kt:coil-compose:2.7.0")
}
