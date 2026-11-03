plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.shadow)
}

allprojects {
    group = "pandastic.studio"
    version = "1.0.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        // Paper 26.3 is compiled for Java 25; matching it keeps the library loadable.
        toolchain.languageVersion = JavaLanguageVersion.of(25)
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation", "-Xlint:unchecked"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    configurations.configureEach {
        // VaultAPI drags in org.bukkit:bukkit (Spigot 1.13) transitively, which conflicts with
        // paper-api's org.bukkit capability. Nothing in this project may ever compile against
        // Spigot's jar instead of Paper's, so the exclusion is global rather than per-dependency.
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // Shared compile-time libraries, handed to every module from here. No module has to
    // declare them and no module has to depend on another one to get them.
    // compileOnly keeps them off the runtime classpath, so they never land in the shaded
    // jar: the server already provides Paper, and the annotation jars are compile-time only.
    dependencies {
        "compileOnly"(rootProject.libs.paper.api)
        "compileOnly"(rootProject.libs.essetials.api)
        "compileOnly"(rootProject.libs.vault.api)
        "compileOnly"(rootProject.libs.floodgate.api)
        "compileOnly"(rootProject.libs.annotations)
        "compileOnly"(rootProject.libs.jspecify)
    }
}

// The root project holds no sources. It exists to fuse every module into the single
// shaded artifact that consumers depend on.
dependencies {
    api(project(":pandastic-core-bootstrap"))
    api(project(":pandastic-core-bridge-contract"))
    api(project(":pandastic-core-service-api"))
    api(project(":pandastic-core-annotation-processor"))
    api(project(":pandastic-core-dialogs"))
    api(project(":pandastic-core-config"))
    api(project(":pandastic-core-database"))
    api(project(":pandastic-core-services"))
    api(project(":pandastic-core-util"))
}

tasks {
    jar {
        // Keep the plain (empty) root jar out of the way of the shaded one.
        archiveClassifier = "thin"
    }

    shadowJar {
        archiveClassifier = ""
        // Needed once pandastic-core-annotation-processor registers itself under META-INF/services.
        mergeServiceFiles()
    }

    assemble {
        dependsOn(shadowJar)
    }
}

// ---------------------------------------------------------------------------
// Publishing — GitHub Packages, one publication per push via CI.
//
// The published component is the SHADED jar, deliberately not components["java"]:
// the java component's POM would declare the nine subprojects as dependencies while
// the jar already embeds their classes, so every Maven consumer would resolve the
// code twice. The shadow component's POM declares no dependencies at all.
//
// Credentials: this repository is public, so nothing that resembles a token may live
// in a versioned file — not even a gitignored one next to it. They are read from the
// environment, which is already how CI supplies them (the workflow exports GITHUB_ACTOR
// and the automatic GITHUB_TOKEN), with gpr.user / gpr.token Gradle properties as the
// fallback for publishing by hand: those belong in ~/.gradle/gradle.properties, outside
// the checkout entirely. An absent credential resolves to the empty string so that
// every other task in the build keeps working on a machine that cannot publish.
// ---------------------------------------------------------------------------
fun secret(vararg sources: Provider<String>): String =
    sources.firstNotNullOfOrNull { source -> source.orNull?.ifEmpty { null } } ?: ""

publishing {
    publications {
        create<MavenPublication>("shadow") {
            from(components["shadow"])
            artifactId = "pandastic-core"
            pom {
                name = "pandastic-core"
                description = "Shaded cross-plugin runtime library for Paper: shared services, " +
                        "caches and a classloader bridge for the Pandastic plugin suite."
                url = "https://github.com/AlepandoCR/pandastic-core"
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AlepandoCR/pandastic-core")
            credentials {
                username = secret(
                    providers.environmentVariable("GITHUB_ACTOR"),
                    providers.environmentVariable("GITHUB_USER"),
                    providers.gradleProperty("gpr.user"),
                )
                password = secret(
                    providers.environmentVariable("GITHUB_TOKEN"),
                    providers.gradleProperty("gpr.token"),
                )
            }
        }
    }
}
