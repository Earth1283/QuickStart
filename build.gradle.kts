plugins {
    kotlin("jvm") version "2.5.0-RC"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"
val sqliteJdbc = "org.xerial:sqlite-jdbc:3.49.1.0"

dependencies {
    compileOnly(paperApi)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly(sqliteJdbc)
    testImplementation(paperApi)
    testImplementation(sqliteJdbc)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("plain")
        destinationDirectory.set(layout.buildDirectory.dir("intermediates/jar"))
    }

    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        from("LICENSE") {
            into("META-INF")
            rename { "LICENSE-QuickStart" }
        }
        filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
    }

    withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    runServer {
        minecraftVersion("26.1.2")
        jvmArgs("-Xms2G", "-Xmx2G", "--enable-native-access=ALL-UNNAMED", "-Dcom.mojang.eula.agree=true")
    }

    test {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }

    processResources {
        val properties = mapOf("version" to version)
        inputs.properties(properties)
        filesMatching("plugin.yml") { expand(properties) }
    }
}
