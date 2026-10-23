plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.stillreader.app"
    compileSdk = 37
    defaultConfig {
        // Keep opt-in PDF benchmarks isolated from the user's installed reader/library.
        applicationId = if (providers.gradleProperty("pdfEvaluationBuild").isPresent)
            "com.stillreader.app.pdfevaluation" else "com.stillreader.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 23
        versionName = "0.1.22"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isJniDebuggable = false
        }
    }
    sourceSets {
        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
        getByName("main").assets.srcDir("../../../shared/resources")
        getByName("test").resources.srcDir("../../../shared/fixtures")
        getByName("test").resources.srcDir("../../../shared/resources")
        getByName("androidTest").assets.srcDir("../../../shared/fixtures")
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// Only small synthetic EPUBs are generated. Downloaded books are optional and never fetched by a build.
val generateEpubFixtures by tasks.registering(Exec::class) {
    val script = rootProject.file("../../scripts/epub-fixtures.py")
    inputs.file(script)
    outputs.files(listOf("structured", "legacy", "fixed-layout", "encrypted", "unsafe-entity", "missing-spine", "path-escape", "image-only").map {
        rootProject.file("../../shared/fixtures/epub/$it.epub")
    })
    commandLine("python3", script.absolutePath)
}
tasks.matching { it.name.contains("Test") }.configureEach {
    dependsOn(generateEpubFixtures)
}

val copyProjectLicense by tasks.registering(Copy::class) {
    from(rootProject.file("../../LICENSE"))
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
    rename { "Still-LICENSE.txt" }
}
tasks.named("preBuild") { dependsOn(copyProjectLicense) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.legere:pdfiumandroid:2.0.3")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
