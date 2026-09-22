
import mihon.gradle.getBuildTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    kotlin("plugin.parcelize")

    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)

    id("com.github.ben-manes.versions")
}

// MihonSY: this fork does not ship Firebase telemetry (no google-services.json), so the
// Google services / Crashlytics plugins are intentionally not applied, even for Release builds.
// MihonSY <--

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "eu.kanade.mihonsy"

        versionCode = 8
        versionName = "1.0.7"

        buildConfigField("String", "UPSTREAM_VERSION", """"0.20.1"""")

        buildConfigField("String", "COMMIT_COUNT", "\"${getLatestCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getLatestCommitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = false)}\"")
        buildConfigField("boolean", "INCLUDE_UPDATER", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("mihonsy") {
            storeFile = file("../keystore/mihonmod.jks")
            storePassword = System.getenv("MIHONSY_STORE_PASSWORD") ?: "mihonmod123"
            keyAlias = "mihonmod"
            keyPassword = System.getenv("MIHONSY_KEY_PASSWORD") ?: "mihonmod123"
        }
    }

    // MihonSY: lightweight native enhancement (Anime4K GPU shaders + Lanczos3 CPU resampler)
    // Uses the locally installed NDK instead of the global one to avoid an extra SDK download.
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // ANDROID_STL defaults to c++_static in the NDK; passing it via `arguments`
            // is unnecessary on AGP 9 (DSL changed), so it is intentionally omitted.
        }
    }

    buildTypes {
        named("debug") {
            versionNameSuffix = "-${getLatestCommitCount()}"
            applicationIdSuffix = ".debug"
            isPseudoLocalesEnabled = true
        }
        named("release") {
            val shrink = !project.hasProperty("disable-code-shrink")
            isMinifyEnabled = shrink
            isShrinkResources = shrink
            isProfileable = true
            signingConfig = signingConfigs.getByName("mihonsy")
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"))

            buildConfigField("String", "BUILD_TIME", "\"${getBuildTime(useLatestCommitTime = true)}\"")
            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
        create("foss") {
            initWith(getByName("release"))

            applicationIdSuffix = ".foss"

            matchingFallbacks.add("release")

            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
        create("benchmark") {
            initWith(getByName("release"))

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks.add("release")
            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            buildConfigField("boolean", "INCLUDE_UPDATER", "false")
        }
    }

    sourceSets {
        getByName("release").java.directories.add("src/release/java")
        getByName("foss").java.directories.add("src/foss/java")
        getByName("debug").java.directories.add("src/debug/java")
        getByName("benchmark").java.directories.add("src/debug/java")
        getByName("benchmark").res.directories.add("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            reset()
            // MihonSY (2026-09-22): AI 引擎只有 arm64-v8a 实现 —— ncnn 静态库只编了 arm64，
            // QNN 运行库（jniLibs）也只有 arm64，x86/x86_64 只服务模拟器、不带任何 AI 后端，
            // armeabi-v7a 同理。故默认只出 arm64 一个 ABI。
            //
            //   -PmihonsyAbi=arm64-v8a        收敛到单 ABI（CI 日常包）
            //   -PmihonsyUniversal=true       额外要一份合并包（正式发版：arm64 + universal）
            //
            // ⚠️ 下面所有赋值都在 reset() 之后，是 include / isUniversalApk 的唯一来源；
            // 不要把 isUniversalApk 提到 reset() 之前，会被 reset 清掉。
            val wantUniversal = (project.findProperty("mihonsyUniversal") as String?) == "true"
            val requestedAbis = (project.findProperty("mihonsyAbi") as String?)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
            if (requestedAbis.isNullOrEmpty()) {
                include("arm64-v8a")
                isUniversalApk = wantUniversal
            } else {
                include(*requestedAbis.toTypedArray())
                isUniversalApk = requestedAbis.size > 1 || wantUniversal
            }
        }
    }

    packaging {
        jniLibs {
            // MihonSY (2026-09-22): 高通 DSP 是通过 ADSP_LIBRARY_PATH 按**路径**自己加载
            // libQnnHtpV<arch>Skel.so 的，不能从 APK 内直接 mmap 出来的库 dlopen。
            // AGP 默认的 extractNativeLibs=false 会让 nativeLibraryDir 全空，于是
            //   Failed to load skel, error: 4000 / Transport layer setup failed: 14001
            // 打开此项与 Komiho 侧保持一致（其参考实现 app.mihon 1.3.9 也是 true）。
            // 代价是机器上要展开这些库；APK 下载体积影响很小（.so 压缩率高）。
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIcons)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.androidx.sqlite.bundled)
    // SY -->
    implementation(sylibs.sqlcipher)
    // SY <--

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.sqldelight.async)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Crash reports/analytics
//    "standardImplementation"(platform(libs.firebase.bom))
//    "standardImplementation"(libs.firebase.analytics)
//    "standardImplementation"(libs.firebase.crashlytics)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)

    // SY -->
    // Firebase (EH)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)
    implementation(sylibs.google.api.client.oauth)

    // Koin
    implementation(sylibs.koin.core)
    implementation(sylibs.koin.android)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)
}

androidComponents {
    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
