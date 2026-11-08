plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Fireworks for true-sim's GPU particles: shells lifted and burst at their apex into patterns of stars " +
    "coloured by their chemistry, burst lights, lingering lit smoke, sound events, and scripted shows from the scene."

dependencies {
    api(project(":effects-particles")) // the particles, their kernel and the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.194.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
    testImplementation("com.truesim.effects:effects-post:0.2.0") // bloom and auto exposure in the films
}

// The fireworks' particle modules (fireworks/patterns.slang) with the built-in ones: kernel variants of their own.
slang {
    javaPackage = "com.truesim.effects.particles.fireworks.hooks"
    pushPackage = "com.truesim.effects.particles.fireworks.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants"
    particleModules = "com.truesim.effects.particles.fireworks.FireworkModules"
}

// The benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
