plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Значение необязательного свойства Gradle в виде строкового литерала для BuildConfig. */
fun quoted(name: String): String = "\"${(findProperty(name) as String?).orEmpty()}\""

android {
    namespace = "ru.finhelper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.finhelper.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Параметры Firebase Cloud Messaging задаются в gradle.properties (см. README.md).
        buildConfigField("String", "FCM_PROJECT_ID", quoted("fcmProjectId"))
        buildConfigField("String", "FCM_APPLICATION_ID", quoted("fcmApplicationId"))
        buildConfigField("String", "FCM_API_KEY", quoted("fcmApiKey"))
        buildConfigField("String", "FCM_SENDER_ID", quoted("fcmSenderId"))
    }

    buildTypes {
        debug {
            // Эмулятор Android обращается к компьютеру разработчика по адресу 10.0.2.2.
            buildConfigField("String", "DEFAULT_BASE_URL", "\"http://10.0.2.2:8000/\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // В выпускаемой сборке допускается только защищенный протокол HTTPS (п. 4.1.2.1 ТЗ).
            buildConfigField("String", "DEFAULT_BASE_URL", "\"https://api.finhelper.example/\"")
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
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*")
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // Каталог снимков экранов: при запуске с -Pscreenshots снимки
                // записываются в mobile/screenshots, иначе – в каталог сборки.
                val target = if (project.hasProperty("screenshots")) {
                    rootProject.file("screenshots")
                } else {
                    layout.buildDirectory.dir("outputs/screenshots").get().asFile
                }
                it.systemProperty("screenshots.dir", target.absolutePath)
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.mlkit.text.recognition)
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
