# Pitfalls: mistakes made once, so they are not made twice

Each entry is a mistake that really happened in this repository, in the engine's
[docs/pitfalls.md](https://github.com/true-sim/engine/blob/main/docs/pitfalls.md) form: what went wrong, how that was
established, the rule that follows, and where the proof is. Only verified things go here: a measurement, a reproduced
failure, a test that fails without the fix, or a reading of the code that shows the fault.

## Shaders

**A fragment's `SV_Position.w` is 1 / w, not the view depth.** The per-pixel and six-way lit draws looked their light
clusters and shadows up at `v.position.w` of the fragment shader's `SV_Position`, which is `FragCoord.w`: 1 / clip w.
Past a few centimetres it is below the clusters' near distance, so every lit particle read the first depth slice (it ends
0.15 m out with the default settings): point and spot lights lit only particles next to the camera, and the sun's
cascades were always the first, so a far particle in a later cascade's shadow was lit.
- Proof: swapping in `mul(view.viewProjection, float4(v.world, 1)).w`, as the engine's surfaces take their depth
  (`meshlets/common.slang`), turned smoke above a fire orange from the fire's light. `LitParticlesTest`'s
  `particlesLitPerPixelFarFromTheCameraTakeTheLightsNextToThem` (mean red 1.46 with a point light 1.5 m below a cloud
  20 m out, the same as without it) and `particlesLitPerPixelFarFromTheCameraTakeTheSunsShadowFromTheirCascade` (22.1
  under the roof against 22.2 in the open) failed before the fix, pass after it.
- Rule: a depth for clusters or cascades is the clip w of the world position (`pixelOf` in draw.slang). Read
  `SV_Position` in a fragment shader for its x and y only, and only where the target's pixels are the ones meant.

**The View's `outputSize` is 0: the engine never sets it.** Per-vertex lighting (`lightMain`) took a particle's pixel
from `View.outputSize`, which only `RenderView.output(width, height)` writes, and nothing in the engine calls it (only
a test). With a size of 0 every particle's pixel was (0, 0): the top left tile's cluster, which a light next to a
particle in the middle of the view does not reach. With one tile across (64 x 64 views, tiles of 64 pixels, as the
older tests drew) it could not show.
- Proof: a dump from `lightMain` (one point light at (0, -1, -20), range 8, one particle at (0, 0.5, -20), a 256 x 256
  view in 4 x 4 tiles of 64 pixels): `outputSize` (0, 0), pixel (0, 0), depth 20 (slice 13 of 24), tile (0, 0),
  cluster 208 holding 0 lights, light 0.245 (the sky alone); the same lookup at the port's size: pixel (128, 124),
  tile (2, 1), 1 light, irradiance 17.7. `LitParticlesTest.particlesLitPerVertexFarFromTheCameraTakeTheLightsNextToThem`
  failed before the fix (1.46 with the light and without), passes after it.
- Rule: a draw record carries the pixels the light clusters are binned over (`ParticleDrawRecord.pixels`, the view
  port's size, as `ClusteredLights` bins over it), and both lighting paths find their pixel by projecting into that
  size; the half-resolution draws too, whose fragments' pixels are half the clusters'
  (`particlesLitPerPixelAtHalfResolutionTakeTheLightsNextToThem` failed before as well). Check a View field is written
  before reading it; and test a per-tile lookup with more than one tile.

**A release inlines the engine's push sizes: 0.4.0 crashed on engine 0.203.** `ParticleDraw`'s half-resolution depth
runs the engine's `blocks/pyramid.spv` with the engine's generated `PyramidPush`; `PyramidPush.BYTES` is a compile-time
constant, so the jar built against engine 0.194 kept 32 while 0.203's shader (a `copy` word added for `DepthCopy`) takes
40, and pipeline creation failed ("pipeline takes 32 bytes of push constants: 40") on the first frame with smoke.
- Proof: true-sim/effects-particles#7 (the showcase on engine 0.203 with 0.4.0); built against 0.203 the pass runs.
- Rule: a release of this library works with the engine it was built on; an application raising the engine takes the
  release built against it. Push layouts, `ViewLayout` offsets and other generated constants are inlined into the jar.

**Pointers that name nothing are the engine's absent address, tested with `given(p)`.** Since engine 0.203 (#457,
#461) the engine hands shaders `VulkanDevice.absentAddress()` (zeros, its lowest bit set) instead of 0, and its
shading tests pointers with bindless.slang's `given(p)`; lavapipe loads through 0 even in branches nobody takes. The
draws' records gave 0 for no light list and no indirect light and tested `== nullptr`: correct with 0, wrong with the
absent address. They now write `ctx.addressOrAbsent` / `ctx.absent()` and test `given(p)`, as the engine's shading does.
- Proof: reading of engine 0.203's `PassContext`, `RenderView` and `lights.slang` (`lightList` tests `given(h)`); the
  lit-particle tests pass with the records holding the absent address.
- Rule: never write 0 for a pointer a shader may load through; write the absent address and test with `given(p)`.

## Tests

**A view without an environment is still lit by a constant sky.** `FireworksGpuTest`'s ground under a burst's light,
drawn by clustered forward shading with no `EnvironmentLighting`, was measured against I cos / d² times albedo / pi as
if the burst's light were all there was: the ratio came out 1.16 +- 0.067, rising from 1.07 under the light to 1.30 at
the image's corners, which no falloff error explains but a constant added light does (the farther, darker points gain
the most from it).
- Proof: subtracting the same pixels drawn before the burst (only that constant light) gave 0.986 +- 0.004 over 64
  points from 49 to 77 m from the light.
- Rule: a test measuring one light's contribution subtracts a frame without it (as `ParticleLightsTest` compares against
  the dark floor), rather than assuming an unlit view is black.

**Past an emitter's survivors its range holds stale particles.** Each frame copies the survivors of a range, in order,
into the other half of the pool; the slots after them keep what an earlier frame left there, ages and lifetimes that
look alive. `FireworksGpuTest` read a burst's whole range after some of its stars had died and found spawn index 208
twice.
- Proof: reading only while every star of the burst still burns (to 1.33 s, the shortest life 1.6 s) found each index
  once.
- Rule: read a range back only as far as its emitter's alive count, or while none of its particles can have died.

**A library's shading hook listing built against an older engine drew no instances: the fireworks' GPU tests read 0.**
Raised to engine 0.203, `FireworksGpuTest`'s ground under a burst read luminance 0 (expected 1) and `SmokeGpuTest`'s
puff let through -0.002 of the wall behind it (expected 0.639). Neither was the smoke or the lighting: no opaque
instance was drawn at all, even an emissive one, with the particle draws taken out of the graph. The test classpath
holds effects-atmosphere's `atmosphere-render` 0.4.0 (the films' night sky), whose listing `AtmosphereRenderShadingHooks`
(probe volume, aerial perspective, fog) holds every hook found, so the engine chose it and loaded its variants of the
engine's shading, compiled against engine 0.192. Engine 0.203 writes the absent address for a view's instance id list
(`ViewLayout.IDS`, count 0); 0.192's culling tested it `!= 0`, took the view for one listing no instances and culled
everything. The "negative transmittance" was the wall missing: the left puff minus the middle one, both on black.
- Proof: a scene of one emissive box read (0, 0, 0) with `ShadingHooks.of(device)` naming `AtmosphereRenderShadingHooks`;
  with the tests' own listing (`FireworksShadingHooks`, built here against 0.203 with the same hooks, installed by
  `Stage`) it read 0.427, the ground under the burst 0.986 +- 0.004 of I cos / d² and the puff let through 0.667
  (e^-tau 0.639), every fireworks test passing (true-sim/effects-particles#7).
- Rule: tests whose classpath holds a library with a listing (a hook library's) build a listing of their own and
  install it (`ShadingHooks.use`): two listings of the same hooks tie, and the engine then takes either. A frame that is
  black where nothing should be is checked for which listing the device runs before the shading is suspected.
