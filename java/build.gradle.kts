plugins {
    `java-library`
    id("me.champeau.jmh") version "0.7.2"
}

group = "com.gridness"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

jmh {
    warmupIterations = 2
    iterations = 3
    warmup = "3s"
    timeOnIteration = "3s"
    fork = 1
    timeUnit = "ms"
    if (project.hasProperty("matchingConfig")) {
        jvmArgs = listOf("-Dgridness.bench.matchingConfig=true")
    }
}

tasks.register<JavaExec>("viewer") {
    description = "Launch the Swing live viewer (walls + heatmap + per-tick latency)."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gridness.viz.GridnessViewer")
    // Pass-through args: gradle viewer --args="city_768 build"
    standardInput = System.`in`
}

tasks.register<JavaExec>("dumpHeatmaps") {
    description = "Compute Java's gridness heatmap for each named fixture and write text files."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gridness.viz.HeatmapDumper")
}
