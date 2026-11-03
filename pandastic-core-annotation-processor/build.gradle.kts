// This module is intentionally free of project dependencies.
//
// Every module that declares annotated types (pandastic-core-config, pandastic-core-bridge-contract, pandastic-core-database, ...)
// applies this processor through `annotationProcessor(project(":pandastic-core-annotation-processor"))`. A
// compile dependency in the opposite direction would make `pandastic-core-config:compileJava` require
// `pandastic-core-annotation-processor:jar` while `pandastic-core-annotation-processor:compileJava` requires
// `pandastic-core-config:jar`, which Gradle rejects as a circular task dependency.
//
// The processors therefore never reference the annotation types as classes; they resolve them
// through the `javax.lang.model` mirror API using the fully qualified names declared at the top of
// each processor.
dependencies {
    // @AutoService is compile-time only; the companion processor writes the
    // META-INF/services/javax.annotation.processing.Processor entries for us.
    compileOnly(libs.auto.service.annotations)
    annotationProcessor(libs.auto.service)
}
