plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "GPU particles for true-sim: scene-effects' effects simulated, sorted and drawn in the engine's frame " +
    "graph, and a world's effects synced from the scene."

dependencies {
    api("com.truesim.engine:engine-scene:0.187.0") // the renderer, the scene bridge and its resources
    api("com.truesim.scene:scene-effects:0.70.0") // effects as data, their spawns and the Java reference
    api("com.truesim.scene:scene-animation:0.70.0") // animation events and curves driving effects
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.187.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
}

// The particle records and constants of src/main/slang (particles/particle.slang), as Java.
slang {
    pushPackage = "com.truesim.effects.particles.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
    constantModules = listOf("particles/particle") // ParticleConstants
    particleModules = "com.truesim.effects.particles.BuiltInModules" // the built-in particle modules
}

// The benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
