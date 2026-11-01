// Builds offline packs on GitHub Actions, reusing the app's own data code unchanged.
plugins {
    kotlin("jvm") version "2.2.20"
    application
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        kotlin { srcDir("../app/src/main/java/app/wayfinder/data") }
    }
}

application {
    mainClass.set("PackerKt")
    applicationDefaultJvmArgs = listOf("-Xmx5g")
}
