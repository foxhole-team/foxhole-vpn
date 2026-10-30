import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class PreparePageAlignedNativeTask : Exec() {
    @get:OutputDirectory
    abstract val nativeDirectory: DirectoryProperty
}

abstract class PrepareVpnBaseAssetsTask : Sync() {
    @get:OutputDirectory
    abstract val assetDirectory: DirectoryProperty
}

val releaseSigningFile = providers.gradleProperty("foxhole.releaseSigningProperties").orNull?.let(rootProject::file)
val releaseSigning = releaseSigningFile?.let { file ->
    require(file.isFile) { "Release signing properties file is missing" }
    Properties().apply { file.inputStream().use(::load) }
}
val releaseKeystore = releaseSigning?.let { properties ->
    val configured = File(requireNotNull(properties.getProperty("storeFile")))
    val parent = requireNotNull(releaseSigningFile).parentFile
    val candidates = if (configured.isAbsolute) listOf(configured, parent.resolve(configured.name))
        else listOf(parent.resolve(configured.path), rootProject.file(configured.path))
    requireNotNull(candidates.firstOrNull { it.isFile }) { "Release signing keystore is missing" }
}
val selectedAbis = providers.gradleProperty("foxhole.abis").orElse("arm64-v8a,armeabi-v7a,x86,x86_64").get()
    .split(' ', ',').filter(String::isNotBlank).distinct()
require(selectedAbis.isNotEmpty() && selectedAbis.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
val appUpdateChannel = providers.gradleProperty("foxhole.updateChannel").orElse("github").get()
require(appUpdateChannel in setOf("github", "fdroid"))
require(selectedAbis.toSet() != setOf("arm64-v8a", "armeabi-v7a", "x86_64") || appUpdateChannel == "fdroid") {
    "The android3 ABI profile is reserved for the F-Droid channel"
}
val instrumentationBuild = providers.gradleProperty("foxhole.androidTestBuildType").orElse("debug").get()
require(instrumentationBuild in setOf("debug", "routerLab", "routerReview"))

android {
    namespace = "com.foxhole.vpn"
    packaging { jniLibs.pickFirsts += "**/libandroidx.graphics.path.so" }
    compileSdk = 37
    sourceSets.getByName("main").assets.setSrcDirs(emptyList<String>())
    testBuildType = instrumentationBuild
    if (instrumentationBuild == "routerReview") {
        lint.lintConfig = file("src/routerReview/lint.xml")
    }

    defaultConfig {
        applicationId = "com.foxhole.vpn"
        minSdk = 26
        targetSdk = 37
        versionCode = 42
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += selectedAbis }
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"$appUpdateChannel\"")
        buildConfigField("String", "UPDATE_MANIFEST_ASSET", "\"${if (selectedAbis == listOf("armeabi-v7a")) "update-manifest-armeabi-v7a.json" else "update-manifest.json"}\"")
    }

    if (providers.gradleProperty("foxhole.splitApks").map(String::toBoolean).orElse(false).get()) {
        splits {
            abi {
                isEnable = true
                reset()
                include(*selectedAbis.toTypedArray())
                isUniversalApk = true
            }
        }
    }

    bundle {
        language { enableSplit = false }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
                storeType = releaseSigning.getProperty("storeType", "PKCS12")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        create("routerLab") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".routerlab"
            matchingFallbacks += "debug"
        }
        create("routerReview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".routerreview"
            versionNameSuffix = "-RouterReview"
            matchingFallbacks += "debug"
        }
        release {
            if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    if (instrumentationBuild == "routerLab") {
        sourceSets.getByName("androidTest").java.srcDir("src/routerLabAndroidTest/kotlin")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    constraints {
        implementation("androidx.room:room-runtime:2.8.4") {
            because("Align WorkManager's database with the verified Guard runtime")
        }
    }
    implementation(project(":vpn-profile"))
    implementation(project(":vpn-engine"))
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

val pageAlignedNdk = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse(
        providers.environmentVariable("ANDROID_SDK_ROOT")
            .orElse(providers.environmentVariable("ANDROID_HOME"))
            .map { "$it/ndk/29.0.14206865" },
    )

val preparePageAlignedNative = tasks.register<PreparePageAlignedNativeTask>("preparePageAlignedNative") {
    nativeDirectory.set(layout.buildDirectory.dir("generated/pageAlignedNative"))
    inputs.file(rootProject.file("config/native/page-aligned-native.json"))
    inputs.files(
        listOf(
            "build-page-aligned-native.sh", "prepare-page-aligned-native-inputs.py",
            "build-graphics-page-aligned.py", "page_aligned_native_common.py",
            "verify-native-pages.py", "verify-native-host-paths.sh",
        ).map { rootProject.file("scripts/$it") },
    )
    inputs.file(pageAlignedNdk.map { rootProject.file("$it/source.properties") })
    inputs.files(pageAlignedNdk.map { ndk ->
        listOf("clang", "clang++", "ld.lld", "llvm-strip", "llvm-ar", "llvm-ranlib", "llvm-nm", "llvm-readelf")
            .map { rootProject.file("$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/$it") }
    })
    doFirst {
        environment("FOXHOLE_NATIVE_OFFLINE", providers.gradleProperty("foxhole.nativeOffline").orElse("false").get().let {
            if (it.toBoolean()) "1" else "0"
        })
        commandLine(
            "bash", rootProject.file("scripts/build-page-aligned-native.sh").absolutePath,
            nativeDirectory.get().asFile.absolutePath,
            layout.buildDirectory.dir("pageAlignedNativeWork").get().asFile.absolutePath,
            pageAlignedNdk.get(),
        )
    }
}

val prepareVpnBaseAssets = tasks.register<PrepareVpnBaseAssetsTask>("prepareVpnBaseAssets") {
    assetDirectory.set(layout.buildDirectory.dir("generated/baseAssets"))
    from("src/main/assets") { exclude("licenses/**") }
    into(assetDirectory)
}

val prepareDerivedNativeLicenseAssets = tasks.register<PreparePageAlignedNativeTask>("prepareDerivedNativeLicenseAssets") {
    dependsOn(preparePageAlignedNative)
    nativeDirectory.set(layout.buildDirectory.dir("generated/derivedLicenseAssets"))
    inputs.dir(rootProject.file("app/src/main/assets/licenses"))
    inputs.dir(preparePageAlignedNative.flatMap { it.nativeDirectory })
    inputs.property("androidAbis", selectedAbis)
    inputs.files(
        rootProject.file("scripts/prepare-derived-native-license-assets.py"),
        rootProject.file("scripts/copy-derived-native-licenses.py"),
        rootProject.file("scripts/verify-page-aligned-native.py"),
        rootProject.file("scripts/rustls-fork-origin.py"),
        rootProject.file("config/foxcore-android-delivery.json"),
        rootProject.file("config/foxcore-source-manifest.json"),
        rootProject.file("config/foxcore-revision.txt"),
    )
    doFirst {
        val coreDeliveryProfile = when (selectedAbis.toSet()) {
            setOf("arm64-v8a", "x86_64") -> "android64"
            setOf("armeabi-v7a") -> "android-armv7"
            setOf("arm64-v8a", "armeabi-v7a", "x86_64") -> "android3"
            setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") -> "android4"
            else -> error("Core legal delivery requires exactly android64, android-armv7, android3 or android4 ABIs")
        }
        commandLine(
            listOf(
                "python3", rootProject.file("scripts/prepare-derived-native-license-assets.py").absolutePath,
                "--repo", rootProject.projectDir.absolutePath,
                "--native", preparePageAlignedNative.get().nativeDirectory.get().asFile.absolutePath,
                "--output", nativeDirectory.get().dir("licenses").asFile.absolutePath,
                "--abis",
            ) + selectedAbis + listOf("--android-delivery-profile", coreDeliveryProfile),
        )
    }
}

androidComponents {
    onVariants { variant ->
        val review = variant.name == "routerReview"
        val nativeDirectory = if (review) layout.buildDirectory.dir("generated/pageAlignedNative")
            else preparePageAlignedNative.flatMap { it.nativeDirectory }
        if (review) {
            variant.sources.jniLibs?.addStaticSourceDirectory(nativeDirectory.get().asFile.absolutePath)
        } else {
            variant.sources.jniLibs?.addGeneratedSourceDirectory(preparePageAlignedNative) { it.nativeDirectory }
        }
        variant.sources.assets?.addGeneratedSourceDirectory(prepareVpnBaseAssets) { it.assetDirectory }
        if (variant.name == "routerReview") {
            val reviewAssets = providers.gradleProperty("foxhole.routerReviewAssets")
                .orElse(layout.buildDirectory.dir("generated/routerReviewAssets").map { it.asFile.absolutePath })
            variant.sources.assets?.addStaticSourceDirectory(reviewAssets.get())
        } else {
            variant.sources.assets?.addGeneratedSourceDirectory(prepareDerivedNativeLicenseAssets) { it.nativeDirectory }
        }
        val variantName = variant.name.replaceFirstChar(Char::uppercaseChar)
        val verifyMerged = tasks.register<Exec>("verify${variantName}PageAlignedNative") {
            if (!review) dependsOn(preparePageAlignedNative)
            val merged = variant.artifacts.get(SingleArtifact.MERGED_NATIVE_LIBS)
            inputs.dir(merged)
            inputs.dir(nativeDirectory)
            doFirst {
                commandLine(
                    listOf(
                        "python3", rootProject.file("scripts/verify-page-aligned-native.py").absolutePath,
                        "--repo", rootProject.projectDir.absolutePath,
                        "--native", nativeDirectory.get().asFile.absolutePath,
                        "--merged", merged.get().asFile.absolutePath,
                        "--abis",
                    ) + selectedAbis,
                )
            }
        }
        val verifyApk = tasks.register<Exec>("verify${variantName}PageAlignedNativeApk") {
            if (!review) dependsOn(preparePageAlignedNative)
            val apks = variant.artifacts.get(SingleArtifact.APK)
            inputs.dir(apks)
            inputs.dir(nativeDirectory)
            doFirst {
                commandLine(
                    listOf(
                        "python3", rootProject.file("scripts/verify-page-aligned-native.py").absolutePath,
                        "--repo", rootProject.projectDir.absolutePath,
                        "--native", nativeDirectory.get().asFile.absolutePath,
                        "--apk-directory", apks.get().asFile.absolutePath,
                        "--abis",
                    ) + selectedAbis,
                )
            }
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            dependsOn(verifyMerged, verifyApk)
        }
        tasks.matching { it.name == "bundle$variantName" }.configureEach {
            dependsOn(verifyMerged)
        }
        if (appUpdateChannel == "github" && variant.name != "routerReview") {
            variant.sources.manifests.addStaticManifestFile("src/githubUpdater/AndroidManifest.xml")
        }
    }
}

val verifyNativePayload = tasks.register<Exec>("verifyNativePayload") {
    val root = providers.environmentVariable("FOXCORE_VPN_JNI_ROOT")
        .orElse(rootProject.layout.projectDirectory.dir("native-libs").asFile.absolutePath)
    commandLine(listOf("python3", rootProject.file("scripts/verify-native-payload.py").absolutePath, root.get()) + selectedAbis)
}

tasks.matching { it.name in setOf("assembleDebug", "assembleRelease", "bundleRelease", "assembleRouterLab", "assembleRouterReview") }.configureEach {
    dependsOn(verifyNativePayload)
}
