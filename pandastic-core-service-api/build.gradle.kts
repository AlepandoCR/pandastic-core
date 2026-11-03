dependencies {
    // The bridge annotation DSL and the shared Dto/Describable contracts.
    api(project(":pandastic-core-bridge-contract"))
    api(project(":pandastic-core-util"))

    // BridgeContractProcessor reads the @BridgeApi interfaces, the @BridgeAnchorKey constants and
    // the @BridgeContractVersion carrier declared in THIS module and generates
    // pandastic.studio.core.bootstrap.api.GeneratedBridgeContract / GeneratedBridgeIds. Those annotations
    // are SOURCE-retention and the generated contract has a single fixed FQN, so the entire
    // annotated surface must be compiled together in one module — this one.
    annotationProcessor(project(":pandastic-core-annotation-processor"))
}
