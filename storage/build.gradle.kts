plugins {
    id("com.diffplug.spotless")
}

dependencies {
    implementation(project(":core"))
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
