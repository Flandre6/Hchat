plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val releaseStoreFile = rootProject.file("app/keystore/。。.jks")
val releaseStorePassword = providers.environmentVariable("HCAT_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("HCAT_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("HCAT_KEY_PASSWORD").orNull
val hasReleaseSigning = releaseStoreFile.isFile &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "h.Hchat"
    compileSdk = 37

    val autoVersionCode = System.getenv("HCAT_VERSION_CODE")?.toIntOrNull() ?: 1
    val autoVersionName = System.getenv("HCAT_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.0"

    defaultConfig {
        applicationId = "h.Hchat"
        minSdk = 27
        targetSdk = 37
        versionCode = autoVersionCode
        versionName = autoVersionName

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("。。") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("。。")
            }
            isMinifyEnabled = true
            isShrinkResources = true
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
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "DebugProbesKt.bin"
            excludes += "META-INF/*.version"
            excludes += "META-INF/com/android/build/gradle/app-metadata.properties"
            excludes += "META-INF/version-control-info.textproto"
            excludes += "META-INF/**/LICENSE"
            excludes += "META-INF/**/LICENSE.txt"
            excludes += "META-INF/**/NOTICE"
            excludes += "META-INF/**/NOTICE.txt"
            excludes += "kotlin/**"
            excludes += "kotlin-tooling-metadata.json"
            excludes += "frameworks/android/*.apk"
            excludes += "android/attrs.xml"
            excludes += "android/attrs_manifest.xml"
            excludes += "android/res-map.txt"
            excludes += "clst/core.jcst"
            excludes += "export/**"
            excludes += "jadx/core/deobf/conditions/tlds.txt"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        val missing = buildList {
            if (!releaseStoreFile.isFile) add("app/keystore/。。.jks")
            if (releaseStorePassword.isNullOrBlank()) add("HCAT_STORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("HCAT_KEY_ALIAS")
            if (releaseKeyPassword.isNullOrBlank()) add("HCAT_KEY_PASSWORD")
        }
        check(missing.isEmpty()) {
            "正式构建缺少签名配置: ${missing.joinToString()}。调试构建请运行 :app:assembleDebug"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("io.github.billywei01:fastkv:3.0.1")
    implementation("org.luckypray:dexkit:2.0.1")
    implementation("com.github.REAndroid:ARSCLib:V1.3.8")
    implementation("io.github.skylot:jadx-dex-input:1.5.5") {
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.guava:guava:33.5.0-android")
    implementation("com.android.tools.smali:smali-baksmali:3.0.9")
    implementation("com.highcapable.kavaref:kavaref-core:1.1.0")
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alibaba.fastjson2:fastjson2:2.0.61.android8")
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.navigationevent:navigationevent:1.0.2")
    implementation("androidx.navigationevent:navigationevent-compose:1.0.2")
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    implementation(files("libs/miuix-blur-android-0.9.2-patched.aar"))
    implementation(files("libs/miuix-shader-android-0.9.2-patched.aar"))
}

// 构建完成后自动复制 APK 到 dist/
tasks.register<Copy>("copyToDist") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { providers.environmentVariable("HCAT_APK_NAME").orElse("Hchat-release-signed.apk").get() }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyToDist")
}

tasks.matching { it.name == "optimizeReleaseResources" }.configureEach {
    doLast {
        val optimizeDir = layout.buildDirectory
            .dir("intermediates/optimized_processed_res/release/optimizeReleaseResources")
            .get()
            .asFile
        val expected = optimizeDir.resolve("resources-release-optimize.ap_")
        if (!expected.isFile) {
            val fallback = layout.buildDirectory
                .file("intermediates/shrunk_resources_binary_format/release/convertShrunkResourcesToBinaryRelease/shrunk-resources-binary-format-release.ap_")
                .get()
                .asFile
            if (fallback.isFile) {
                fallback.copyTo(expected, overwrite = true)
            }
        }
    }
}
