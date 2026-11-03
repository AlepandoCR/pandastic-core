dependencies {
    api(project(":pandastic-core-bootstrap"))
    implementation(project(":pandastic-core-util"))

    // Nullability annotations are compileOnly for main, so they are absent from the test compile
    // classpath; the tests touch annotated signatures, so javac needs to see them. CLASS retention
    // means they are not needed at test runtime.
    testCompileOnly(rootProject.libs.annotations)
    testCompileOnly(rootProject.libs.jspecify)

    testImplementation(platform(rootProject.libs.junit.bom))
    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)

    // Paper is compileOnly for main because the server provides it, but the profile-service tests
    // construct services around real Plugin and Adventure types, so Bukkit has to be on the test
    // classpath. Scoped to the test source set only.
    testImplementation(rootProject.libs.paper.api)

    // The economy tests implement Vault's Economy interface as a fake; same compileOnly-for-main,
    // test-classpath-for-tests split as Paper.
    testImplementation(rootProject.libs.vault.api)
}
