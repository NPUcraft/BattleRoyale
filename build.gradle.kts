plugins { java }
group = "com.lastsector"
version = "0.1.0-SNAPSHOT"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") { exclude(group="org.slf4j") }
    implementation("com.mysql:mysql-connector-j:9.4.0") { exclude(group="com.google.protobuf") }
    implementation("com.google.code.gson:gson:2.13.2")
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    val mysqlPort = providers.environmentVariable("LASTSECTOR_MYSQL_TEST_PORT")
    inputs.property("mysqlTestPort", mysqlPort.orElse("disabled"))
    useJUnitPlatform { if (!mysqlPort.isPresent) excludeTags("mysql") }
}
tasks.processResources {
    val pluginVersion = project.version.toString()
    filteringCharset = "UTF-8"
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}

// Opt-in public-API probe for real Paper tests. Never included in the installable plugin.
val paperProbe by sourceSets.creating
paperProbe.compileClasspath += sourceSets.main.get().output
configurations[paperProbe.compileOnlyConfigurationName].extendsFrom(configurations.compileOnly.get())
tasks.register<Jar>("paperProbeJar") {
    archiveFileName.set("lastsector-test-probe.jar")
    from(paperProbe.output)
    destinationDirectory.set(layout.buildDirectory.dir("integration"))
}

// Bundle runtime JDBC/codec dependencies; drivers are explicitly loaded, no ServiceLoader merge required.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
}
