subprojects {
    apply(plugin = "java")

    group = "dev.keelfy"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    dependencies {
        val lombok = "org.projectlombok:lombok:1.18.48"
        "compileOnly"(lombok)
        "annotationProcessor"(lombok)
        "testCompileOnly"(lombok)
        "testAnnotationProcessor"(lombok)

        "testImplementation"(platform("org.junit:junit-bom:5.13.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core:3.27.6")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("--source", "21", "--target", "25"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
