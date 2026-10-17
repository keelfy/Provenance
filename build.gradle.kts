plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.keelfy"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.202-stable")

    val lombok = "org.projectlombok:lombok:1.18.48"
    compileOnly(lombok)
    annotationProcessor(lombok)
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.6")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--source", "21", "--target", "25"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.runServer {
    minecraftVersion("26.3")
}
