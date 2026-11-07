package com.truesim.effects.particles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
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
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.EnvironmentMaps;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.ProbeVolume;
import com.truesim.engine.render.ProbeVolumeLighting;
import com.truesim.engine.render.RayTracing;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectRuntime;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Spawn;
import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * Particles lit by a probe volume (the light from all around, per vertex and per pixel): a red glowing wall and a white
 * floor bounce red light onto white puffs in front of the wall. With {@code TRUESIM_PARTICLE_DUMP} set to a directory
 * the image's floats are written there ({@code probe-lit.bin}), to compare builds bit for bit (the shading hooks
 * against the probe volume's table before them).
 */
class ProbeLitParticlesTest {

    private static final int SIZE = 48;
    private static final long FRAME = 16_667;

    @Test
    void particlesTakeTheProbeVolumesLight() throws IOException {
        VulkanInstance instance;
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        try (instance) {
            assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
            float[] image;
            try (VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu())) {
                assumeThat(device.rayTracing()).as("no ray tracing").isTrue();
                image = render(device);
                device.waitIdle();
            }
            assertThat(instance.validationErrors()).as("validation errors").isZero();
            // The puffs' middle row: lit red by the wall's bounce more than green
            double red = 0;
            double green = 0;
            for (int x = 0; x < SIZE; x++) {
                int p = (SIZE / 2) * SIZE + x;
                red += image[4 * p];
                green += image[4 * p + 1];
            }
            assertThat(red).as("red light from all around").isGreaterThan(1.2 * green);
            String dump = System.getenv("TRUESIM_PARTICLE_DUMP");
            if (dump != null) {
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(4 * image.length)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
                bytes.asFloatBuffer().put(image);
                Files.createDirectories(Path.of(dump));
                Files.write(Path.of(dump, "probe-lit.bin"), bytes.array());
            }
        }
    }

    private static float[] render(VulkanDevice device) {
        SceneGraph nodes = new SceneGraph(new ChangeClock());
        Effects effects = new Effects(nodes);
        EffectRuntime runtime = new EffectRuntime(effects);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        try (FrameGraph graph = new FrameGraph(device, 2);
             GeometryPool pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 12, 64, 4), graph.frameSlots());
             GpuScene scene = new GpuScene(device, pool, 8, graph.frameSlots());
             Materials materials = new Materials(device, 8, graph.frameSlots());
             MeshletRenderer renderer = new MeshletRenderer(device, scene, VK_FORMAT_R32G32B32A32_SFLOAT,
                 VK_FORMAT_D32_SFLOAT, graph.frameSlots());
             RayTracing rays = new RayTracing(device, pool, 8);
             Lights lights = new Lights(device, 4, 4, graph.frameSlots());
             Particles particles = new Particles(device, graph.frameSlots(), 1 << 20, 16);
             GpuImage target = new GpuImage(device, SIZE, SIZE, VK_FORMAT_R32G32B32A32_SFLOAT, usage,
                 VK_IMAGE_ASPECT_COLOR_BIT);
             GpuBuffer pixels = GpuBuffer.hostVisible(device, 16L * SIZE * SIZE, VK_BUFFER_USAGE_TRANSFER_DST_BIT)) {
            MeshData quadMesh = TestMeshes.quad();
            int quad = pool.add(quadMesh);
            rays.add(quad, quadMesh, quadMesh.levels().size() - 1);
            MetallicRoughnessParameters mr = MetallicRoughnessParameters.DEFAULTS.withRoughness(1).withMetallic(0);
            int wall = materials.add(new Material(mr.withBaseColor(new Color(0, 0, 0, 1))
                .withEmissive(new Color(3, 0.3f, 0.3f, 1))).doubleSided(true));
            int floor = materials.add(new Material(mr.withBaseColor(new Color(0.8f, 0.8f, 0.8f, 1)))
                .doubleSided(true));
            scene.setMaterials(materials);
            scene.set(0, quad, wall, 0, 0, -7, 0, 0, 0, 1, 4, 4, 4);
            scene.set(1, quad, floor, 0, -2, -4, -0.70710677f, 0, 0, 0.70710677f, 5, 5, 5);
            lights.setCount(0);
            graph.settings().set(ProbeVolume.CASCADES, 1).set(ProbeVolume.PROBES, 8).set(ProbeVolume.PROBES_UP, 8)
                .set(ProbeVolume.SPACING, 1.25f).set(ProbeVolume.RAYS, 128);
            Camera camera = new Camera().set(0, 0, -1, 0, 1, 0, Math.PI / 2, SIZE, SIZE, 0.1);
            SceneGeometry geometry = SceneGeometry.meshlets(renderer);
            ViewPort port = graph.addView("main").size(SIZE, SIZE);
            SceneView view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
            EnvironmentMaps.Quality small = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
            Environment environment = Environment.uniform("dim", 0.02f, 0.02f, 0.02f);
            port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R32G32B32A32_SFLOAT, VK_FORMAT_D32_SFLOAT)
                    .clearColor(0, 0, 0))
                .add(new EnvironmentLighting(environment, view, small))
                .add(new ProbeVolumeLighting(view, new ProbeVolume(rays, renderer, lights, environment, small, view)))
                .add(new ParticleDraw(particles, view)).add(new Capture(pixels))
                .output(Keys.SCENE_COLOR, new ExternalImage("target", SIZE, SIZE, Format.R32G32B32A32_SFLOAT, usage,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(),
                    target.view()));
            graph.add(new ParticleSimulation(particles));
            puffs(effects, -1.2f, Renderer.Lighting.PER_VERTEX);
            puffs(effects, 1.2f, Renderer.Lighting.PER_PIXEL);
            for (int f = 0; f < 40; f++) {
                nodes.update();
                runtime.update(FRAME);
                graph.beginFrame();
                particles.update(effects, runtime.spawns());
                view.set(1, MeshletRenderer.MODE_LIT);
                graph.submit(0, 0);
            }
            device.waitIdle();
            float[] image = pixels.memory().asSlice(0, 16L * SIZE * SIZE).toArray(ValueLayout.JAVA_FLOAT);
            scene.setMaterials(null);
            return image;
        }
    }

    /** Three white puffs in a row at x = {@code x}, lit as {@code lighting}, 3 m ahead. */
    private static void puffs(Effects effects, float x, Renderer.Lighting lighting) {
        effects.play(EffectData.builder().emitter("e", e -> e.capacity(3).spawn(Spawn.burst(0, 3))
            .init(Init.lifetime(100), Init.size(Bind.constant(1.2f)), Init.color(Bind.color(1, 1, 1, 1)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(lighting))).build(), x, 0, -3);
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
