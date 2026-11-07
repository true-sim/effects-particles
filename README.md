# true-sim effects-particles

GPU particles for true-sim: scene-effects' effects (`EffectData`, played in `Effects`) simulated, sorted and drawn in
the engine's frame graph, and a scene's effects synced every frame. It is an optional library over the engine's core
and uses only the core's generic hooks:

- the radix sort block (`RadixSort`, `blocks/radix.slang`) for events, ribbons and back-to-front billboards;
- named order points (`Transparency.ID`) to draw after the blended surfaces, before or split at glass;
- the settings registry (`effects.quality`, `effects.budgetMegabytes`, listed with every other setting);
- GPU-written instances and lights (`GpuScene.reserveGpuRange`, `Lights.gpuLights`) for mesh particles and particle
  lights, ray tracing's top level for ray collisions;
- the engine's Slang modules (`bindless`, `meshlets.common`, `lights`, `raytracing`), imported from its jars;
- `SceneResources` for every texture, mesh, material and vector field effects name (`hold`, `texture`, `mesh`,
  `holdMaterials`, `residency`, and an uploader for `EffectAssets.VECTOR_FIELD`), so content loads through the asset
  cache like everything else.

| Module | Contents |
|---|---|
| `effects-particles` | `Particles` (the pool, programs and per-frame tables), `ParticleSimulation`, `ParticleDraw`, `ParticleCollisionDepth`, the particle modules' kernel (`[ParticleModule]` structs, `BuiltInModules`); `scene`: `EffectSync`, `EffectsPlugin`, `EffectBindings` |

```kotlin
dependencies {
    implementation("com.truesim.effects:effects-particles:0.1.0") // with engine-scene 0.187 and scene-effects 0.68
}
```

The design is scene's [docs/effects.md](https://github.com/true-sim/scene/blob/main/docs/effects.md).

## The simulation and drawing

`com.truesim.effects.particles` simulates scene-effects' effects (`EffectData`, played in `Effects`, spawned by
`EffectRuntime`) on the GPU, as the scene repository's `docs/effects.md` designs them. Every GPU emitter of a playing
effect is an emitter instance with a range of the shared `ParticlePool` (two copies of 80-byte particles, the layout
generated from `particles/particle.slang`), reserved from its capacity in chunks of 64 slots. Each effect's emitters
are encoded once into module programs and curve keys (`ModulePrograms`), which one interpreting kernel runs.

The content effects name comes through the scene's asset system: the textures (`texture`, `normal`, `texture.b`),
meshes and materials (`Renderer.mesh(mesh, material)`) and vector fields (`Forces.field`, scene-effects'
`EffectAssets.VECTOR_FIELD`) are held in the engine's `SceneResources` while an effect naming them is encoded, loaded
through the asset cache and drawn from where the resources put them, followed as they arrive or leave
(`SceneResources.residency`). Until one is on the GPU a texture draws as none (a round sprite), a mesh renderer draws
nothing and a vector field pushes nothing. Nothing is registered by hand.

```java
Particles particles = new Particles(device, graph.frameSlots(), 64 << 20)    // a pool budget in bytes
    .assets(resources);                              // textures, meshes and vector fields through the asset cache
graph.add(new ParticleSimulation(particles));        // provides ParticleKeys.PARTICLES and STATE
sceneViews.addPerView(view -> new ParticleDraw(particles, view));
// each frame, before or after beginFrame:
runtime.update(seconds);
particles.update(effects, runtime.spawns());
```

- **A frame's simulation** (`ParticleSimulation`, on the graphics queue): count the survivors of each chunk, scan
  them per emitter, update them (forces integrated exactly, attributes over life from curve keys kept in group-shared
  memory per chunk) and copy them in order into the other half of the pool, clamp each emitter's spawns to its room
  (overflow counted), spawn the newborn a thread each from the spawn records (placed along the emitter's motion by
  birth time), then keep the counts. Spawn order is kept across chunks; values depend only on seeds, never on slots,
  threads or atomics.
- **The same as the Java reference:** the kernel runs scene-effects' `CpuSimulation` modules with the same hash,
  salts and order; the tests run each module's asset as a GPU and a CPU emitter side by side and compare particle by
  particle, and check the hash against scene-effects' `hash-vectors.csv`.
- **Ribbons, trails and beams** (E13), camera-facing strips drawn by `ParticleDraw` too:
  - ribbons through an emitter's particles in spawn order (the survivor copy keeps it across chunks), their head at
    the emitter so there is no gap behind it, every `stride`-th particle by spawn index a point (doubling with distance
    past `lodDistance`), and ribbons of several strands (`ribbons`) ordered by the second key (strand, spawn index) in a
    "particle ribbons" pass;
  - trails through each particle's last positions (`Renderer.trail(points)`), kept in a ring in its side range, copied
    with the survivors; emitters with trails hold at most `Particles.MAX_TRAIL_PARTICLES`;
  - beams of noisy segments from the emitter to an end (`particles.beamEnd(effect, emitter, x, y, z)`, or a node
    through `EffectSync.beamEnd`), or up its `length` along the emitter's +Y;
  - textures stretched over a strip or tiled (`tile`), widths from tail to head (`width.tail`, `width.head`).
  10,000 sparks with trails of 16 points: update 0.027 ms, draw 0.083 ms on an RX 9070 XT (64 x 64 pixels).
- **CPU emitters** (E1: `Target.CPU`, simulated by scene-effects' `CpuSimulation`, which gameplay reads): given
  to `particles.update(effects, spawns, simulation)`, their particles are written each frame into a mapped buffer and
  "particle upload" copies them into their ranges of the pool, so drawing, culling and sorting treat them like GPU
  particles. A game simulating particles itself writes them through `particles.writer(effect, emitter)`
  (`writer.clear(); writer.next().position(x, y, z).velocity(...).color(...).size(...)`), allocation-free, instead of
  the simulation's. The same asset as a CPU and as a GPU emitter draws the same image (tested).
- **World and local space:** world-space particles are float offsets from their effect's origin (two floats per axis
  on the GPU); local-space ones live in the emitter's frame.
- **Statistics** (alive, spawned, dropped; per instance and in all) come back through a readback a few frames late,
  for budgets and debugging.
- **Drawing** (`ParticleDraw`, a technique per view, `addPerView`): each emitter instance's GPU box is culled against
  the view's frustum (its indirect draw gets no instances), then every billboard renderer draws, emitters as wholes
  back to front by distance, after the blended materials (`Transparency.ID`; not interleaved with sorted ones),
  within the view's render region.
  Billboards face the camera's position, the view plane, their velocity (stretched by speed) or their emitter's up
  axis; blending is premultiplied, alpha to additive by the renderer's `alphaToAdd`; flipbooks play over life or by
  age, frames blended; soft particles fade into the opaque depth over a distance, or by the view ray's path through
  the particle's sphere (`volume`); unlit. Textures are texture assets, resolved through the scene resources;
  without one a round sprite. The renderer's settings by name: `texture`, `stretch`, `soft`, `volume`,
  `flipbook.columns`, `flipbook.rows`, `flipbook.frames`, `flipbook.fps`, `flipbook.blend`.

- **Scalability** (effects step 18): effect types' levels of detail reach the GPU, the drawn fraction fading over
  0.3 s (each particle kept or faded out by its hash, the kept grown to keep the cover) and far emitters updating
  every n frames, drawn moved on by their velocity between; budgets in bytes (`EffectRuntime.budget`, the pool's,
  set by `EffectSync`): past three quarters the least significant spawn at half, past all of it they are culled, by
  their types' priority first; renderers drawn at half resolution (`half`, or every billboard at low quality) into a
  target of half the view's size, laid over it by a depth-aware upsample; `ParticleSimulation.async(true)` on the
  async compute queue (not with mesh particles, mesh shapes or rays); `ParticleDraw.overdraw(true)`, a heat map.
- **Shared simulations** (E23, `EffectData.Builder.shared(variants, nearDistance)`): an asset whose modules allow it
  (GPU, local space, no collision, mesh shapes, mesh, light or beam renderers) simulates a few variants, each drawn at
  every placement showing it, moved, turned and scaled as the placement is; placements near a viewer simulate on their
  own, the swap faded. A thousand torches are six simulations.
- **Particles as emitters** (E2, `ParticleEvent.emit(target, perSecond)`): fixed-point accumulators per particle,
  children spread along the parent's path; ribbons per parent (`ribbons` 0), ordered by a radix sort of their parents'
  spawn indices: 100,000 sparks each with a trail.
- **Modules from their Slang structs** (E24): every module, built in (particles/modules) or a game's, is one
  `[ParticleModule("name")]` struct, its fields its parameters and its functions its stages (`init`, `force`, `after`,
  `collide`, `update`, `over`); the build generates the kernel's dispatch, the opcodes and the Java listing
  (`BuiltInModules`, a game's `slang.particleModules` class). A game's modules come with kernel variants of its own
  (base and rays) and, optionally, Java twins for CPU emitters (`[ModuleTwin("...")]`, scene-effects' `ModuleTwin`);
  techniques may modify `ParticleKeys.PARTICLES` after the simulation. Built in too: `Init.rotation`, `Init.frame`,
  `Shape.mesh(mode, upward)`.

  ```slang
  [ParticleModule("game.gust")]
  [ModuleTwin("com.example.game.GustTwin")] // optional: CPU emitters may have it
  public struct Gust
  {
      public float strength = 1;
      public void update(inout ParticleStep s, inout Particle p) { p.velocity.x += strength * s.h; }
  };
  ```

  ```java
  particles.modules(GameModules.MODULES);   // slang { particleModules = "com.example.game.GameModules" }
  EffectData leaves = EffectData.builder().emitter(...).build(GameModules.MODULES.registry());
  ```
- **Shading hooks** (engine-render's README, "Writing a shading hook"): the draws run the view's hooks as its surfaces
  do, made with its hooks' constants (`ShadingHookInputs`): lit particles take the light from all around through the
  indirect hooks (the probe volume where it reaches, the environment elsewhere), and every particle's light goes through
  the media hooks on its way to the camera (fog; the light they add in front of it where it covers what is behind, so
  nothing is fogged twice). No surface hook. An emitter takes its node's override entry
  (`EffectSync.hookOverrides(ShadingHookOverrideSync)`, engine-scene's per-node overrides): a node turning the fog off
  turns it off for the effects under it as for its renderables. A game's hook listing rebuilds these draws with its
  hooks (this jar's `shading-shaders.index`); the tests' listing does too (the test sources' fog, `test/hooks`).
- **Glass order** (`glass` 1: drawn by `ParticleDraw.beforeGlass`, tinted by the glass in front; 2: split at a view's
  `ParticleKeys.GLASS_DEPTH`) and TAA hooks: the reactive mask (`ParticleKeys.REACTIVE`) and mesh particles'
  transforms a frame before (`ParticleKeys.PREVIOUS_INSTANCES`).

A million particles update in 0.27 ms on an RX 9070 XT under gravity and drag; 8 curves over life add 0.15 ms, whether
they have 2 or 8 keys, evenly spaced or not, so the cost is the attributes kept from birth, not the key search
(`ParticleBenchmark`, 2026-11-02). More of `ParticleBenchmark` (RX 9070 XT, 2026-11-02):

| | ms |
|---|---|
| a million particles: the view's sort (10,000: 0.064, 100,000: 0.109) | 0.73 |
| a million unlit sparks drawn, 1920 x 1080 | 1.39 |
| the simulation of a million, graphics queue / async compute: the frame's span | 2.79 / 2.60 |
| 600 lit smoke puffs of 3 m over a Quest 3's eye (2064 x 2208): full / half resolution | 4.2 / 1.4 |
| the same at 1920 x 1080 | 1.75 / 0.50 |

## A scene's effects

`com.truesim.effects.particles.scene`: `EffectSync` follows a scene's `Effects` as `SceneSync` follows its
renderables. Each frame (between frames or while one records) it runs the emitter logic and the CPU emitters and hands
the frame to `Particles`. As a plugin, `EffectsPlugin` puts the simulation in the frame graph and syncs one world's
effects after the game's update, with the quality setting `effects.quality` and the pool's `effects.budgetMegabytes`
(listed by the engine's settings registry). With the engine's `Assets` plugin installed, the content effects name loads
through its cache and resources, and `.fga` files import as vector fields.

```java
app.use(new Rendering(), new Assets().mount("game", Path.of("assets")), new EffectsPlugin());
EffectsPlugin fx = engine.get(EffectsPlugin.KEY);
Effects effects = fx.effects(scene);                       // synced every frame from now on
fx.draw(sceneViews);                                      // a ParticleDraw in every view
Effect torch = effects.play(TORCH, handNode).offset(0, 0.4, 0).inheritVelocity(1);   // joints are nodes: sockets
fx.sync().beamEnd(zap.id(), 0, targetNode);                 // a beam from its emitter to another node
effects.replace(TORCH, editedTorch);                       // hot reload: instances restart, handles kept
```

- **Attached** effects follow their node (a joint's for a socket, with an offset); world-space emitters spawn along its
  motion and inherit `inheritVelocity` of it; local-space ones carry their particles along. Stop, kill, time scales
  and pauses (an effect's, all effects', or a paused world's step of 0) act through the emitter records, so paused
  effects stay as they are while every view draws them (tested).
- **Origin rebasing:** once an effect is `Particles.REBASE_DISTANCE` (4,096 m) from the origin of its world-space
  particles, the origin moves to it and the update pass shifts its particles (and trails) by the difference: an effect
  7,000 km out draws the same image as at the origin (tested, rebasing every ten frames).
- **Pooling:** an ended effect's ranges and programs stay warm for `Particles.WARM_FRAMES` (300) for the next instance
  of the same asset (at most 4 per asset, given back early when the pool lacks room); a replaced asset's instances keep
  the ranges that still fit.
- **Animation drives effects** (`EffectBindings`, `fx.bindings(animators)` on the plugin): `onEvent(event, effect,
  joint)` plays the effect at that joint of whichever animator passes the event (found by the node its listener hears
  of), the event's weight as a parameter with `scale(parameter)`; `curve(animator, curve, effect, parameter)` sets the
  parameter from the animator's curve every frame. The samples' fox kicks up dust on its four foot-down events and its
  survey's curve flickers the torch.
- Interpolation between fixed steps (application-api D91) is not built: effects take nodes' world transforms as they
  are. 100 torches on rocks in the engine's scene benchmark allocated 0 bytes per frame (median), measured before
  the move.

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene` and `engine` are used as included builds when present
(override with `-Ptruesim.<repo>=<path>`, or `false` to use the published packages); otherwise the published packages
are used. The GPU tests run validated on any Vulkan 1.3 device (CI: Mesa's lavapipe, in test shards balanced by
`ci/test-timings.txt`); the benchmarks run with `TRUESIM_BENCHMARK=true`.

Before changing the shaders, read [docs/pitfalls.md](docs/pitfalls.md) (and the engine's): mistakes made here once,
with their evidence and the rule that follows.

## Licence

MIT, see [LICENSE](LICENSE).
