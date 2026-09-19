plugins { java }
group = "com.lastsector"
version = "0.1.0-SNAPSHOT"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
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
