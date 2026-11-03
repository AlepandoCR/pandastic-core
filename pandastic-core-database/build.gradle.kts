dependencies {
    api(project(":pandastic-core-config"))
}

dependencies {
    testImplementation(platform(rootProject.libs.junit.bom))
    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)

    // Paper is compileOnly for main; the async tests construct the manager around a fake Plugin,
    // so Bukkit types must be on the test classpath.
    testImplementation(rootProject.libs.paper.api)
    testCompileOnly(rootProject.libs.annotations)
}
