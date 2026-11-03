dependencies {
    // ConfigContractProcessor reads the @Configurable classes declared in this module and
    // generates their GeneratedConfig implementations. Annotation processing only sees the
    // sources of the module being compiled, so the processor has to be applied here.
    //
    // This is cycle-free only because pandastic-core-annotation-processor has no project dependencies:
    // it reads annotations through the javax.lang.model mirror API rather than referencing
    // the annotation classes. Do not give the processor a dependency on this module.
    annotationProcessor(project(":pandastic-core-annotation-processor"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Paper is compileOnly for main (the server provides it), and compileOnly does not reach the
    // test classpath. The generated configuration holds a YamlConfiguration field, so the tests
    // need Paper on both their compile and runtime classpath to load that class at all.
    testImplementation(libs.paper.api)
}
