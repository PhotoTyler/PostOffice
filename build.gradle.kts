plugins {
    java
}

group = "click.portl"
version = "1.6.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.135-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("PostOffice-${project.version}.jar")
}

// Zips the resource pack folder into build/PostOffice-pack.zip
val packZip by tasks.registering(Zip::class) {
    from("resourcepack")
    archiveFileName.set("PostOffice-pack.zip")
    destinationDirectory.set(layout.buildDirectory)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.build {
    dependsOn(packZip)
}
