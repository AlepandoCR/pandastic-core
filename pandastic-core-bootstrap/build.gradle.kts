dependencies {
    // The bridged service API, the generated bridge contract, and (transitively, as api) the
    // annotation DSL plus the Dto/Describable contracts. The follower-side classes in this module
    // implement that API — PandasticReflectiveApi is a PandasticCore, PandasticBridgeServiceManager
    // is a PandasticServiceManager — which is why the API lives one module below and not here.
    api(project(":pandastic-core-service-api"))

    // Nullability annotations are compileOnly for main, so they are absent from the test compile
    // classpath; the tests touch annotated signatures, so javac needs to see them. CLASS retention
    // means they are not needed at test runtime.
    testCompileOnly(rootProject.libs.annotations)
    testCompileOnly(rootProject.libs.jspecify)

    testImplementation(platform(rootProject.libs.junit.bom))
    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)

    // Paper is compileOnly for main because the server provides it, but the bridge tests load
    // library classes for real, so Adventure and Bukkit have to be on the test runtime classpath
    // too. Scoped to the test source set only; the main classpath still comes from the root build.
    testImplementation(rootProject.libs.paper.api)
}
