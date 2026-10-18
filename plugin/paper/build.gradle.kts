plugins {
    id("xyz.jpenilla.run-paper")
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":storage"))
    
    compileOnly("com.google.inject:guice:7.0.0")
    compileOnly("io.papermc.paper:paper-api:26.3.build.202-stable")
}

// ReportJson needs Gson, which the server provides at runtime.
configurations.testImplementation {
    extendsFrom(configurations.compileClasspath.get())
}

base {
    archivesName = rootProject.name
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

// Плагин — один jar: core вшивается внутрь (paper-api — compileOnly, не попадает).
tasks.jar {
    from(configurations.runtimeClasspath.map { cp -> cp.map(::zipTree) })
}

tasks.runServer {
    minecraftVersion("26.3")
    runDirectory = rootProject.layout.projectDirectory.dir("run")
}
