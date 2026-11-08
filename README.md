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
| `effects-particles-fireworks` | Fireworks ([below](#fireworks)): shells, burst patterns (`fireworks.pattern`, `fireworks.strobe`, `FireworkModules`), colorants from emission spectra, burst lights, smoke, sound events, scripted shows (`FireworkShow`, `.fireworks` assets, the `FireworkDisplay` component), `FireworksSync`, `FireworksPlugin` |

```kotlin
dependencies {
    implementation("com.truesim.effects:effects-particles:0.1.0") // with engine-scene 0.187 and scene-effects 0.68
    implementation("com.truesim.effects:effects-particles-fireworks:0.3.0") // fireworks, with engine-scene 0.194
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

## Fireworks

`effects-particles-fireworks` (`com.truesim.effects.particles.fireworks`) is fireworks over these particles: shells
lifted from mortars that burst at their apex into patterns of stars coloured by their chemistry, in physical units,
each burst lighting the scene and the smoke the shells leave, sounds for audio, and scripted shows played from the
scene. Everything is the particles' and the scene's own: the shells' effects are scene-effects data on the GPU
particles, the lights are scene light components, the shows are scene components and assets.

```java
app.use(new Rendering(), new Assets().mount("game", Path.of("assets")), new EffectsPlugin(), new FireworksPlugin());
Effects effects = engine.get(EffectsPlugin.KEY).effects(scene);
FireworksSync fireworks = engine.get(FireworksPlugin.KEY).play(effects);   // the scene's shows, each frame
AssetRef<FireworkShow> show = paths.ref(FireworkAssets.SHOW, "game://shows/new-year.fireworks");
cache.acquire(show);                                                      // ... once loaded:
scene.components().set(field, FireworkDisplay.TYPE, FireworkDisplay.of(cache.get(show)));
heard = fireworks.sounds().forEachSince(heard, (s, slot, id) -> audio.play(s.kind(slot), s.position(slot, 0),
    s.position(slot, 1), s.position(slot, 2), s.level(slot)));           // launches, bursts, crackles
```

- **Shells** (`Shell`: a pattern, a colorant, a second colorant, a calibre in inches) are designed from their calibre
  (`ShellDesign`): they burst some 33 m up per inch and 10 m wide per inch (`fireworks.burstHeight`,
  `fireworks.burstRadius`), so a mortar fires them at the speed that reaches that height against the air's drag, and
  their stars leave at the speed that carries them to the radius as the air slows them.
- **Motion is physical and in closed form** (`Ballistics`): gravity and linear drag through moving air,
  dv/dt = g - k (v - w), which is the particle kernel's own exact integration of `force.gravity` and `force.wind` (a
  drag towards the world's wind, `Effects.wind`, without gusts). A shell's apex and its time are known in closed form:
  the sync bursts it there and then, in the frame the apex comes. A burst's centre (its stars' mean: their directions
  sum to nothing, and the motion is linear in the velocity) moves as one shell would, and its light follows it.
- **Palms** open wide: their arms leave 57 to 77 degrees from the vertical and fly the shell's radius, so their reach
  out is most of a peony's of the calibre (real palms' fronds spread about as wide), drooping from there; their comets
  are heavy stars, three times a star's light.
- **Patterns** (`Pattern`), laid out by the pattern module `fireworks.pattern` by each star's order of birth: a sphere by
  the golden angle (peony, chrysanthemum, willow, strobe, crackle), a ring in a tilted plane, a palm's arms on a cone, a
  crossette's cross per group of four children; jittered a little. A peony or chrysanthemum with a second colorant has
  a pistil (an inner sphere). Chrysanthemums leave charcoal sparks; willows burn long charcoal stars in heavy drag, their
  glitter hanging and drooping; palms throw a few heavy comets; crossettes split a third of the way out into crosses
  of four; strobes blink (`fireworks.strobe`, each star its own phase and rate); crackles burst as they burn out into
  pellets that pop a moment later, each a flash of a micro-star. The module's Java mirror (`Pattern.direction`) is the
  tests' reference.
- **Colours from emission spectra** (`Colorant`, `Spectra`): strontium red (SrCl, SrOH bands), barium green (BaCl),
  copper blue (CuCl), sodium yellow (the D lines), calcium orange, purple (Sr and Cu), magnalium white, titanium silver
  and charcoal gold (hot bodies at 4,600, 3,400 and 1,900 K), integrated against the CIE 1931 matching functions
  (Wyman et al.'s fit) into linear Rec. 709 at a luminance of 1, out-of-gamut lines mixed with white of the same
  luminance. Each colorant has a star's luminous intensity in candela (blue the dimmest, sodium the brightest).
- **Physical units**: a star of intensity I drawn as a round sprite of the glow's size s (`fireworks.glow`) has the
  luminance 8 I / (pi s²) (the sprite's profile integrates to half its disc), times `fireworks.luminanceScale` (scene
  colour per cd/m²: 1, or a renderer's pre-exposure). Brightness falls as stars burn out (a flare at ignition, burning,
  burning out). Stars and sparks are drawn as streaks of their speed over `fireworks.shutter` (motion blur) and at
  least `fireworks.minPixels` across, their light kept either way (the particles' new `conserve` and `minPixels`
  renderer settings, below), so a far star is a steady point of the right brightness and the very bright cores bloom.
- **Burst lights**: each burst lights the scene through a point light (`LightSource`, on a node the sync places, which
  the engine's light sync puts into the clustered lights) at its stars' centre. Its intensity is the sum of its stars'
  as they burn (and the bursting charge's flash, `fireworks.flashIntensity`), its colour theirs, and it reaches as far as
  it gives `fireworks.lightThreshold` lux; at most `fireworks.maxLights` shine at once, the dimmest going first. Smoke
  is lit per vertex by the lights about it, so each burst lights its own smoke and the lingering smoke of those before.
- **Smoke as a participating medium** (`Smoke`): what black powder and stars leave is an aerosol of potassium salts
  with some soot, submicron to a micron across: it scatters far more than it absorbs, mostly forwards. Each puff is a
  Gaussian of mass m whose deviation grows by turbulent diffusion, σ² = σ0² + 2 K t: its mass stays, its density falls
  as it spreads, the optical depth through its centre κ m / (2 π σ²). It scatters ω of what it removes by a
  Henyey-Greenstein phase function of asymmetry g, and the light reaching its centre passes through half of it. Stars
  leave `fireworks.smokeMass` grams a second in `fireworks.smoke` puffs a second along their paths, a shell's bursting
  charge `fireworks.burstSmoke` grams per square inch of calibre about its centre, the lift a little; κ
  (`fireworks.smokeExtinction`, 3 m²/g, as obscurant aerosols of that size measure in the visible), ω
  (`fireworks.smokeAlbedo`, 0.9), g (`fireworks.smokePhase`, 0.6), K (`fireworks.smokeDiffusivity`, 0.5 m²/s), σ0
  (`fireworks.smokePuff`) and its time (`fireworks.smokeTime`) are settings. The particles draw the puffs as such
  (the renderer's new `medium` setting): a Gaussian of optical depth three deviations across, letting through e^-τ of
  what is behind it, lit per vertex by every light about it (the bursts', with their inverse square) through the phase
  function, at half resolution; there are no sprite edges, and a burst's own light lights its smoke from behind it
  towards the viewer far more than from in front.
  - Why not effects-atmosphere's fog: its fog volumes are a few homogeneous ellipsoids per view, not thousands of
    spreading puffs along stars' paths; its froxels, the only part lit by point lights with shadows, reach 300 m (a
    show is watched from 300 to 600 m); and it would make fireworks depend on one sky plugin. Puffs as a medium in the
    particles keep the smoke where the particles are, at any distance, lit by the same clustered lights.
- **Sounds** (`FireworkSounds`, as effects-weather's `LightningEvents`): a ring of launches, bursts and crackle volleys
  with their time, position, level (dB at 1 m) and shell's seed; `arrival` and `forEachArriving` delay each by the
  distance over `fireworks.speedOfSound`.
- **Shows** (`FireworkShow`): a seed and a timeline of cues (time, shell, the mortar's offset in the node's frame and its
  lean), made in code (`builder`, `random`, `finale`) or loaded through the asset cache from `.fireworks` text files
  (`FireworkAssets.SHOW`, imported by the plugin). A `FireworkDisplay` component on a node plays one from when it is
  added. Every shell's stars, turns and tilts come from the show's seed and the cue, so a show plays the same every
  time (tested particle for particle).
- **Settings**: every tunable is a setting of the registry (`FireworksSettings`, `fireworks.*`): intensities, glow,
  shutter, least pixels, burst height and radius, the shells' drag, tail rate, smoke, the lights' threshold and number,
  the speed of sound, the luminance scale.

The particles gained renderer settings for this, any game's to use: `minPixels` (billboards drawn at least this
many pixels across, dimmed by the area they gained) and `conserve` (velocity-stretched billboards dimmed by their
stretch: streaks of the same light), tested in `ParticleDrawTest`; `medium` and `phase` (billboards as Gaussian puffs
of a participating medium: their alpha the optical depth at the centre, their colour the scattering albedo, lit per
vertex through a Henyey-Greenstein phase function and shadowing themselves), tested with the fireworks' smoke in
`SmokeGpuTest`.

Tests (`FireworksTest` on the CPU, `FireworksGpuTest` validated on the GPU): the closed form against a fine
integration, apexes and launch speeds; the colorants' colours; the patterns' layouts (the golden-angle sphere's spacing
and mean, rings in their plane, palms on their cone, crosses at right angles); shows from a seed, their files and
assets; the sync bursting a shell in the frame its apex comes, at the apex the wind carried it to, its light at its
stars' centre as bright as they burn and gone when they are, sounds delayed by their distance, crackles as the stars
burn out, the lights' limit. On the GPU: every star of a burst once, along the module's direction to 2e-4; shells and
stars where the closed form puts them through gravity, drag and wind; a display's shell bursting at its apex, where its
stars begin; the same bytes from the same seed and others from another; and a burst's light on the ground against
I cos / d² times albedo / pi over its four lights: 0.986 +- 0.004 of it at 64 points from 49 to 77 m from the
burst.

A dense finale (`FireworksBenchmark`, `TRUESIM_BENCHMARK=true`; RX 9070 XT, 2026-11-08): 120 shells of every pattern
fired over 8 s, the last sixth at once, over the village of the films at 1920 x 1080 with their whole look (the
atmosphere's night sky, clustered lights, bloom, auto exposure, the display transform); medians over the 4 s after the
salvo, from the graph's per-pass timestamps, JaCoCo off. 288,000 particles alive (most of them smoke puffs) and 96
burst lights (the limit: 24 bursts of 4) at the median.

| | ms |
|---|---|
| particles: update / sort / draw (the smoke as a medium at half resolution included) / the rest | 0.127 / 0.089 / 1.47 / 0.194 |
| the village shaded under 96 burst lights and 4 lamps (meshlets, clustered forward) | 1.41 |
| the atmosphere's sky, its view table and stars / bloom (physical) / auto exposure (histogram) | 0.08 / 0.133 / 0.03 |
| the frame's GPU span (with the test's 0.5 ms capture of scene colour) | 4.39 |
| CPU: the shows (`FireworksSync.update`) / the effects sync | 0.031 / 0.180 |

Films (`FireworksFilms`, `TRUESIM_SCREENSHOTS=true`): a 30 s show over an Alpine village square at night, a close look
at each pattern and a ring's smoke close up, under effects-atmosphere's night sky (`AtmosphereSky`: the bundled star
catalogue, the moon, airglow, the Milky Way, captured into the environment; scene colour pre-exposed at the EV100
`AtmosphereExposure` gives the night, never below -2 so the stars' cores fit half floats), with bloom and auto
exposure (effects-post). effects-post and atmosphere-render are test dependencies only.

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
