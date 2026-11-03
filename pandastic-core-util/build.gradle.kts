dependencies {
    testImplementation(platform(rootProject.libs.junit.bom))
    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)

    // Paper is compileOnly for main (the server provides it); the component tests build real
    // Adventure components, so the API has to be on the test classpath too.
    testImplementation(rootProject.libs.paper.api)
    testCompileOnly(rootProject.libs.annotations)
}
