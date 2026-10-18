// JDBC без драйвера: на сервере sqlite-jdbc уже поставляет Paper, в тестах подключаем сами.
dependencies {
    implementation(project(":core"))
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.50.3.0")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
