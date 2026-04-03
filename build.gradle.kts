plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val perfTestSourceSet = sourceSets.create("perfTest")

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite")
    testImplementation("io.cucumber:cucumber-java:7.18.1")
    testImplementation("io.cucumber:cucumber-core:7.18.1")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.18.1")
    testImplementation("io.rest-assured:rest-assured:5.4.0")
    testImplementation("org.json:json:20240303")
}

configurations[perfTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[perfTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("perfTest") {
    group = "verification"
    description = "Runs the Part C todo performance experiments."
    dependsOn(tasks.named(perfTestSourceSet.classesTaskName))
    classpath = perfTestSourceSet.runtimeClasspath
    mainClass.set("performance.TodoPerformanceRunner")
    workingDir = project.layout.projectDirectory.asFile
    systemProperty("partc.outputDir", project.findProperty("partcOutputDir") ?: "part_c/performance/results/latest")
    systemProperty("partc.iterations", project.findProperty("partcIterations") ?: "5")
    systemProperty("partc.tiers", project.findProperty("partcTiers") ?: "10,50,100,250,500,1000")
    systemProperty("partc.port", project.findProperty("partcPort") ?: "4567")
    systemProperty("partc.seed", project.findProperty("partcSeed") ?: "4292026")
}
