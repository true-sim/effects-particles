plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Fireworks for true-sim's GPU particles: shells lifted and burst at their apex into patterns of stars " +
    "coloured by their chemistry, burst lights, lingering lit smoke, sound events, and scripted shows from the scene."

dependencies {
    api(project(":effects-particles")) // the particles, their kernel and the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.203.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
    testImplementation("com.truesim.effects:effects-post:0.2.0") // bloom and auto exposure in the films
    testImplementation("com.truesim.effects:atmosphere-render:0.4.0") // the films' night sky
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

// The tests' own shading hook listing (FireworksShadingHooks, in the test sources; Stage installs it): every hook of
// the test classpath (the engine's, and effects-atmosphere's for the films) with the engine's and the particles' shaders
// that shade rebuilt with them, against the engine this module builds on. Without it the engine takes the classpath's
// largest listing, atmosphere-render's, whose variants of the engine's shading were compiled against the engine that
// release was built on: on another engine they draw no instances at all (docs/pitfalls.md).
tasks.named<com.truesim.build.slang.GenerateSlangRecords>("generateTestSlangRecords") {
    shadingHooks = "com.truesim.effects.particles.fireworks.hooks.FireworksShadingHooks"
}
// The particles' kernel rebuilt includes the particle modules' dispatch, which only the main sources (declaring
// modules) generate; and the test sources have no shaders of their own, which the plugin compiles only where
// src/test/slang exists: an empty directory stands in.
val testSlangSources = layout.buildDirectory.dir("generated/slang-test-sources")
val testParticleDispatch = tasks.register<Sync>("testParticleDispatch") {
    from(tasks.named("generateSlangRecords").map {
        layout.buildDirectory.dir("generated/slang-records/main/slang/particle-modules")
    })
    into(layout.buildDirectory.dir("generated/slang-test-includes/particle-modules"))
    val empty = testSlangSources
    doLast { empty.get().asFile.mkdirs() }
}
tasks.named<com.truesim.build.slang.CompileSlang>("compileTestSlang") {
    includes.from(files(layout.buildDirectory.dir("generated/slang-test-includes")).builtBy(testParticleDispatch))
    sources = testSlangSources
    dependsOn(testParticleDispatch)
}
