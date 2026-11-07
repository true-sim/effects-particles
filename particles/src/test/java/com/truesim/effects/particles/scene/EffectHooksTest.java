package com.truesim.effects.particles.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.math.Vec3;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.particles.ParticleDraw;
import com.truesim.effects.particles.ParticleSimulation;
import com.truesim.effects.particles.Particles;
import com.truesim.effects.particles.hooks.TestFogParameters;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
import com.truesim.engine.scene.ShadingHookOverride;
import com.truesim.engine.scene.ShadingHookOverrideSync;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Spawn;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Particles under the view's shading hooks, as the scene's renderables: a media hook (the test sources' fog,
 * test/hooks/testFog.slang, rebuilt into the draws by the test listing) fogs particles; a node overriding the fog's
 * values to none, or switching it off ({@link ShadingHookOverride}), leaves the model and the effect under it exactly as
 * without fog, while those outside are fogged ({@link EffectSync#hookOverrides}, the emitter's entry from its node). Validated.
 */
class EffectHooksTest {

    private static final int SIZE = 64;
    static final AssetRef<MeshData> QUAD = AssetRef.generated(RenderAssets.MESH,
        (Generator<Integer, MeshData>) (unused, seed) -> TestMeshes.quad(), 0, 1);
    static final AssetRef<MaterialData> RED = AssetRef.generated(RenderAssets.MATERIAL,
        (Generator<Integer, MaterialData>) (unused, seed) -> MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", 0f, 0f, 0f, 1f).set("emissive", 0.8f, 0.2f, 0.1f).build(),
        0, 1);

    @Test
    void particlesAreFoggedAndAnOverrideUnderANodeLeavesItsModelAndEffectUnfogged() throws Exception {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        int errors;
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                draw(device);
                device.waitIdle();
            }
            errors = instance.validationErrors();
        }
        assertThat(errors).as("validation errors").isZero();
    }

    private static void draw(VulkanDevice device) throws InterruptedException {
        FrameSlots slots = new FrameSlots(2);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        try (GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 10, 16, 4), slots);
             Materials materials = new Materials(device, 16, slots);
             Textures textures = new Textures(device, slots);
             TaskScheduler tasks = new TaskScheduler();
             AssetCache assets = new AssetCache(tasks);
             SceneResources resources = new SceneResources(assets, pool, materials, textures);
             GpuScene gpu = new GpuScene(device, pool, 8, slots);
             MeshletRenderer renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R8G8B8A8_UNORM,
                 VK_FORMAT_D32_SFLOAT, slots);
             Particles particles = new Particles(device, slots, 1 << 20, 8);
             GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R8G8B8A8_UNORM, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, 4L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
             FrameGraph graph = new FrameGraph(device, slots)) {
            SceneGraph scene = new SceneGraph(new ChangeClock());
            ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
            SceneSync sync = new SceneSync(resources, scene, gpu);
            ShadingHookOverrideSync overrides = new ShadingHookOverrideSync(scene, sync, gpu.hookOverrides());
            Effects effects = new Effects(scene);
            EffectSync fx = new EffectSync(effects, particles).hookOverrides(overrides);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new ParticleDraw(particles, view)).add(new Capture(pixels))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R8G8B8A8_UNORM, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(),
                    target.view()));
            graph.add(new ParticleSimulation(particles));
            // A house on the left: a model and an effect under it; the same outside on the right
            long house = scene.create();
            long[] models = {scene.create(house), scene.create()};
            long[] emitters = {scene.create(house), scene.create()};
            EffectData puff = EffectData.builder().emitter("e", e -> e.capacity(1).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(100), Init.size(Bind.constant(1)), Init.color(Bind.color(1, 1, 1, 0.8f)))
                .render(Renderer.billboard(Renderer.Facing.POSITION))).build();
            for (int side = 0; side < 2; side++) {
                double x = side == 0 ? -1.5 : 1.5;
                scene.setTranslation(models[side], x, 0, -5);
                renderables.set(models[side], Renderable.of(QUAD, List.of(RED)));
                scene.setTranslation(emitters[side], x, 0, -4);
                effects.play(puff, emitters[side]);
            }
            Runnable frame = () -> {
                int slot = graph.beginFrame();
                slots.begin(slot);
                tasks.drainCompleted();
                scene.update();
                sync.update();
                overrides.update();
                fx.update(1 / 60.0);
                view.set(1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
                slots.end();
            };
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (sync.instanceCount() < 2) {
                frame.run();
                assertThat(System.nanoTime()).as("meshes loading").isLessThan(deadline);
                Thread.sleep(2);
            }
            int[] clear = frames(frame, device, pixels);
            ShadingHookValues values = ShadingHookValues.of(device);
            values.set("test.fog", new TestFogParameters(new Vec3(0.4, 0.4, 0.4), new Vec3(0, 0.1, 0.3)), true);
            try {
                int[] fogged = frames(frame, device, pixels);
                // The fog alone: everything of both sides fogged, the particles too
                int[] particleLeft = {SIZE / 2 - 12, SIZE / 2};
                int[] particleRight = {SIZE / 2 + 12, SIZE / 2};
                for (int[] p : new int[][] {particleLeft, particleRight}) {
                    int before = clear[p[1] * SIZE + p[0]];
                    int after = fogged[p[1] * SIZE + p[0]];
                    assertThat(red(after)).as("a particle's light through the fog").isLessThan(red(before) - 40);
                    assertThat(blue(after)).as("and the fog's blue light added").isGreaterThan(red(after) + 40);
                }
                // The house turns the fog off: its side as without fog, bit for bit; the other side fogged
                scene.components().set(house, ShadingHookOverride.TYPE, new ShadingHookOverride("test.fog",
                    MaterialData.builder("fog").set("transmittance", 1f, 1f, 1f).set("light", 0f, 0f, 0f).build()
                        .values()));
                int[] overridden = frames(frame, device, pixels);
                assertThat(overrides.entryOf(emitters[0])).as("the house's emitter's entry").isPositive();
                assertThat(overrides.entryOf(emitters[1])).isZero();
                int shown = 0;
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE / 2; x++) {
                        int i = y * SIZE + x;
                        assertThat(overridden[i]).as("in the house, pixel %d, %d", x, y).isEqualTo(clear[i]);
                        shown += clear[i] != clear[0] ? 1 : 0;
                    }
                    for (int x = SIZE / 2; x < SIZE; x++) {
                        int i = y * SIZE + x;
                        assertThat(overridden[i]).as("outside, pixel %d, %d", x, y).isEqualTo(fogged[i]);
                    }
                }
                assertThat(shown).as("the house's model and puff drawn").isGreaterThan(100);
                // The house switches the fog off instead: the same
                scene.components().set(house, ShadingHookOverride.TYPE, ShadingHookOverride.off("test.fog"));
                int[] switched = frames(frame, device, pixels);
                assertThat(switched).as("the fog switched off in the house").containsExactly(overridden);
            } finally {
                values.remove("test.fog");
            }
            device.waitIdle();
            sync.close();
        }
    }

    /** Frames until the image stands; the last image. */
    private static int[] frames(Runnable frame, VulkanDevice device, GpuBuffer pixels) {
        for (int f = 0; f < 6; f++) {
            frame.run();
        }
        device.waitIdle();
        return pixels.memory().asSlice(0, 4L * SIZE * SIZE).toArray(ValueLayout.JAVA_INT);
    }

    private static int red(int rgba) {
        return rgba & 0xff;
    }

    private static int blue(int rgba) {
        return (rgba >> 16) & 0xff;
    }

    /** Copies the scene's color into {@code pixels} after everything drawn over it. */
    private record Capture(GpuBuffer pixels) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            g.pass("capture").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(SIZE).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(SIZE, SIZE, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
