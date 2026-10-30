plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val nativeRoot = providers.environmentVariable("FOXCORE_VPN_JNI_ROOT")
    .orElse(rootProject.layout.projectDirectory.dir("native-libs").asFile.absolutePath)

android {
    namespace = "com.foxhole.vpn.engine"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    sourceSets {
        getByName("main") {
            jniLibs.directories.clear()
            jniLibs.directories.add(nativeRoot.get())
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":vpn-profile"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
