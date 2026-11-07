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
