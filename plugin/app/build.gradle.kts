plugins {
    id("com.diffplug.spotless")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))

    // The host provides Gson at runtime (Paper, Fabric and NeoForge all ship it).
    compileOnly("com.google.code.gson:gson:2.14.0")
}

configurations.testImplementation {
    extendsFrom(configurations.compileClasspath.get())
}
