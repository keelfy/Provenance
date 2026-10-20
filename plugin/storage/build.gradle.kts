plugins {
    id("com.diffplug.spotless")
}

dependencies {
    implementation(project(":core"))

    // Delivered at runtime by ProvenanceLoader; the SQLite driver comes with Paper.
    compileOnly("com.zaxxer:HikariCP:7.1.0")
    testImplementation("com.zaxxer:HikariCP:7.1.0")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
