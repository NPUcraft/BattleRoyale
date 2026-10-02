import java.security.MessageDigest
plugins { java }
group = "com.npucraft.battleroyale"
version = "1.0.0-rc.23"
repositories {
    mavenCentral()
    maven("https://repo.nightexpressdev.com/releases")
    maven("https://api.modrinth.com/maven")
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("maven.modrinth:nightcore:2.15.0") { isTransitive = false }
    compileOnly("maven.modrinth:excellenteconomy:2.7.0") { isTransitive = false }
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") { isTransitive = false }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0") { exclude(group="org.slf4j") }
    implementation("com.mysql:mysql-connector-j:9.4.0") { exclude(group="com.google.protobuf") }
    implementation("com.google.code.gson:gson:2.13.2")
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(25) }
tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
tasks.test {
    val mysqlPort = providers.environmentVariable("BATTLEROYALE_MYSQL_TEST_PORT")
    inputs.property("mysqlTestPort", mysqlPort.orElse("disabled"))
    useJUnitPlatform { if (!mysqlPort.isPresent) excludeTags("mysql") }
    // Opt-in deployment gate: -Dbr.live.config=<plugin data directory> validates a real config set.
    // Forwarded explicitly because the Gradle daemon does not pass -D through to the test JVM.
    val liveConfig = providers.systemProperty("br.live.config").orElse("")
    inputs.property("br.live.config", liveConfig)
    systemProperty("br.live.config", liveConfig.get())
    if (liveConfig.get().isNotBlank()) {
        inputs.dir(liveConfig.get())
        // Only a deployment gate needs the loader's summary on stdout; a plain test run stays quiet.
        testLogging { showStandardStreams = true }
    }
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
    archiveFileName.set("battleroyale-test-probe.jar")
    from(paperProbe.output)
    destinationDirectory.set(layout.buildDirectory.dir("integration"))
}

// Bundle runtime JDBC/codec dependencies; drivers are explicitly loaded, no ServiceLoader merge required.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
}

// Lock the complete resolved dependency graph; Paper remains a provided API.
dependencyLocking { lockAllConfigurations() }
val commitHash = providers.provider {
    try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(rootDir).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && value.matches(Regex("[0-9a-f]{7,40}"))) value else "unknown"
    } catch (_: Exception) { "unknown" }
}
val generateBuildInfo by tasks.registering {
    val output = layout.buildDirectory.file("generated/build-info/battleroyale-build.properties")
    inputs.property("commit", commitHash)
    inputs.property("version", project.version.toString())
    outputs.file(output)
    doLast { output.get().asFile.apply { parentFile.mkdirs(); writeText("commit=${commitHash.get()}\nversion=${project.version}\ntype=release-candidate\njava-target=25\npaper-target=26.2\n") } }
}
tasks.processResources { dependsOn(generateBuildInfo); from(layout.buildDirectory.dir("generated/build-info")) }
tasks.jar { manifest.attributes("Implementation-Version" to project.version, "Build-Type" to "release-candidate", "Java-Target" to "25", "Paper-Target" to "26.2", "Build-Commit" to commitHash.get()) }
val releaseChecksum by tasks.registering {
    dependsOn(tasks.jar)
    val jar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jar)
    val output = jar.map { it.asFile.resolveSibling(it.asFile.name + ".sha256") }
    outputs.file(output)
    doLast { val bytes = MessageDigest.getInstance("SHA-256").digest(jar.get().asFile.readBytes()); output.get().writeText(bytes.joinToString("") { "%02x".format(it) } + "  " + jar.get().asFile.name + "\n") }
}
tasks.build { dependsOn(releaseChecksum) }
val stressTest by sourceSets.creating
stressTest.compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
stressTest.runtimeClasspath += stressTest.output + stressTest.compileClasspath
configurations[stressTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[stressTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("stressTest") {
    description = "Opt-in logical/database stress tests, separate from normal test/check"
    group = "verification"
    dependsOn(tasks.testClasses)
    testClassesDirs = stressTest.output.classesDirs
    classpath = stressTest.runtimeClasspath
    useJUnitPlatform { if (!providers.environmentVariable("BATTLEROYALE_MYSQL_TEST_PORT").isPresent) excludeTags("mysql") }
    inputs.property("mysqlTestPort", providers.environmentVariable("BATTLEROYALE_MYSQL_TEST_PORT").orElse("disabled"))
    maxHeapSize = "1g"
}
