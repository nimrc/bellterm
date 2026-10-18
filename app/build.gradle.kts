import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.bellterm"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "app.bellterm"
        // libghostty-vt's Zig code uses ELF thread-local storage, which Android's
        // loader supports from API 29; older releases refuse the library.
        minSdk = 29
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // A release keystore comes from BELLTERM_* environment variables or Gradle
    // properties of the same names, never from the repository; see README.md.
    fun signing(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotEmpty() } ?: providers.gradleProperty(name).orNull
    val keystore = signing("BELLTERM_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = signing("BELLTERM_KEYSTORE_PASSWORD")
                keyAlias = signing("BELLTERM_KEY_ALIAS")
                keyPassword = signing("BELLTERM_KEY_PASSWORD") ?: signing("BELLTERM_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release keystore the build still installs, signed with the debug key.
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    sourceSets["main"].jniLibs.directories.add(layout.buildDirectory.dir("rustJniLibs").get().asFile.path)
    sourceSets["main"].assets.directories.add(layout.buildDirectory.dir("legalAssets").get().asFile.path)
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

// The Rust core (native/) is built by cargo-ndk into build/rustJniLibs.
// It needs Zig 0.15.2 for libghostty-vt; see README.md.
val cargoProfile = providers.gradleProperty("herdr.rustProfile").orElse("release")
val buildRust = tasks.register<Exec>("buildRust") {
    val native = rootProject.layout.projectDirectory.dir("native")
    val out = layout.buildDirectory.dir("rustJniLibs")
    workingDir = native.asFile
    inputs.dir(native.dir("src"))
    inputs.file(native.file("Cargo.toml"))
    inputs.file(rootProject.layout.projectDirectory.file("Cargo.toml"))
    inputs.file(rootProject.layout.projectDirectory.file("Cargo.lock"))
    inputs.dir(rootProject.layout.projectDirectory.dir("crates/herdr-client/src"))
    inputs.dir(rootProject.layout.projectDirectory.dir("crates/herdr-protocol/src"))
    outputs.dir(out)
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    val sdk = local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    if (System.getenv("ANDROID_NDK_HOME") == null && sdk != null) {
        environment("ANDROID_NDK_HOME", "$sdk/ndk/${android.ndkVersion}")
    }
    local.getProperty("zig.dir")?.let { environment("PATH", "$it:${System.getenv("PATH")}") }
    val profile = cargoProfile.get()
    commandLine(
        buildList {
            addAll(listOf("cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "-P", "29", "-o", out.get().asFile.path, "build", "--locked"))
            if (profile == "release") add("--release")
        },
    )
}
// LICENSE, NOTICE, and the vendored protocol's attribution ship in the APK for
// the licences screen, copied so the repository keeps a single copy of each.
val legalAssets = tasks.register<Copy>("legalAssets") {
    val root = rootProject.layout.projectDirectory
    from(root.file("LICENSE")) { rename { "LICENSE.txt" } }
    from(root.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(root.file("crates/herdr-protocol/NOTICE.md")) { rename { "herdr-protocol-NOTICE.md" } }
    into(layout.buildDirectory.dir("legalAssets/licenses"))
}
tasks.named("preBuild") { dependsOn(buildRust, legalAssets) }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
