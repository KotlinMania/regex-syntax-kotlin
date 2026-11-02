plugins {
    java
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

// Workspace automation stays in tools; standalone projects use only their own buildSrc.
if (projectDir.parentFile.name == "template") {
    sourceSets.main
        .get()
        .java
        .srcDir("../../tools/build-automation/src/main/java")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    // Reuse Gradle's own catalog parser; no separate version or download.
    implementation(
        fileTree(requireNotNull(gradle.gradleHomeDir).resolve("lib")) {
            include(
                "tomlj-*.jar",
                "antlr4-runtime-*.jar",
                "commons-compress-*.jar",
                "commons-io-*.jar",
                "commons-codec-*.jar",
                "commons-lang3-*.jar",
            )
        },
    )
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register<JavaExec>("runBuildTool") {
    group = "verification"
    description = "Runs a Java build helper; pass --args='ToolClass arguments'."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kotlinmania.build.BuildToolMain")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    workingDir(projectDir.parentFile)
    val scratch = projectDir.parentFile.resolve("build/tmp/java-build-tools")
    doFirst { scratch.mkdirs() }
    systemProperty("java.io.tmpdir", scratch.absolutePath)
    environment("TMPDIR", scratch.absolutePath)
    environment("TMP", scratch.absolutePath)
    environment("TEMP", scratch.absolutePath)
}
