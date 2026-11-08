package com.truesim.effects.particles.fireworks;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.particles.ParticleDraw;
import com.truesim.effects.particles.ParticleSimulation;
import com.truesim.effects.particles.Particles;
import com.truesim.effects.particles.scene.EffectSync;
import com.truesim.effects.post.AutoExposure;
import com.truesim.effects.post.Bloom;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.engine.graph.testing.TestTarget;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.scene.LightSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.Effects;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.SplittableRandom;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * The tests' and films' stage: the engine's renderer straight (a test's fixture), a scene graph whose light components
 * the engine's {@link LightSync} places (the bursts' lights), the GPU particles with the fireworks' kernel synced from
 * the scene's effects ({@link EffectSync}) and the scene's shows played by {@link FireworksSync}; drawn by clustered
 * forward shading into 16-bit floats with the particles, then optionally a night sky, bloom (effects-post), auto
 * exposure and the display transform into an sRGB target. Scene colour is in cd/m² (a luminance scale of 1).
 */
final class Stage implements AutoCloseable {

    private static final Key<Image> SHOWN = Key.image("test.fireworksShown");

    final VulkanInstance instance;
    final VulkanDevice device;
    final FrameGraph graph;
    final EngineSettings settings;
    final GeometryPool pool;
    final GpuScene gpu;
    final MeshletRenderer renderer;
    final Materials materials;
    final Textures textures;
    final Lights lights;
    final Particles particles;
    final SceneGraph scene = new SceneGraph(new ChangeClock());
    final Effects effects = new Effects(scene);
    final EffectSync fx;
    final FireworksSync fireworks;
    final LightSync lightSync;
    final Camera camera = new Camera();
    final SceneView view;
    final ViewPort port;
    final int width;
    final int height;
    final GpuBuffer color;
    final com.truesim.engine.vulkan.GpuImage shown;
    final GpuBuffer shownPixels;
    final int box;
    final int rock;
    private int instances;

    /** What the view draws after shading and the particles. */
    enum Look {
        /** Scene colour only, captured (tests). */
        LINEAR,
        /** A night sky behind, bloom, auto exposure and the display transform into sRGB (films). */
        FILM
    }

    /**
     * A stage of {@code width} x {@code height} pixels, validated or not (films), with a particle pool of
     * {@code budgetMegabytes}.
     */
    Stage(int width, int height, boolean validated, int budgetMegabytes, Look look) {
        this.width = width;
        this.height = height;
        VulkanInstance made = null;
        try {
            made = VulkanInstance.builder().applicationName("fireworks").validation(validated)
                .synchronizationValidation(validated).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            org.assertj.core.api.Assumptions.assumeThat(e).as("no Vulkan available").isNull();
        }
        instance = made;
        if (instance.gpus().isEmpty()) {
            instance.close();
            org.assertj.core.api.Assumptions.assumeThat(false).as("no Vulkan 1.3 GPU").isTrue();
        }
        device = VulkanDevice.createHeadless(instance.bestGpu());
        graph = new FrameGraph(device, 2);
        settings = graph.settings();
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 18, 8192, 8), graph.frameSlots());
        gpu = new GpuScene(device, pool, 2048, graph.frameSlots());
        renderer = new MeshletRenderer(device, gpu, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT,
            graph.frameSlots());
        materials = new Materials(device, 64, graph.frameSlots());
        gpu.setMaterials(materials);
        textures = new Textures(device, graph.frameSlots());
        lights = new Lights(device, 128, 4, graph.frameSlots());
        lights.setCount(0);
        particles = new Particles(device, graph.frameSlots(), (long) budgetMegabytes << 20)
            .modules(FireworkModules.MODULES);
        fx = new EffectSync(effects, particles);
        fireworks = new FireworksSync(effects, settings);
        lightSync = new LightSync(scene, lights);
        box = add(TestMeshes.box());
        rock = add(TestMeshes.rock(3));
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        port = graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(1 << 14, 1 << 20));
        color = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
            .clearColor(0, 0, 0));
        if (look == Look.FILM) {
            int sky = textures.add(TextureFormat.RGBA16F, 2048, 1024, Textures.GENERATE, nightSky(2048, 1024));
            Environment environment = Environment.panorama("night sky", sky, 2048);
            port.add(new EnvironmentLighting(environment, view)).add(new SkyBackground(environment, view));
        }
        port.add(new ClusteredLights(lights, view)).add(new ParticleDraw(particles, view));
        int shownUsage = TestTarget.USAGE | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
        // the display transform's output: sRGB-encoded values in half floats, as the engine's tests read it
        Format shownFormat = Format.R16G16B16A16_SFLOAT;
        shown = new com.truesim.engine.vulkan.GpuImage(device, width, height, shownFormat.vk(), shownUsage,
            VK_IMAGE_ASPECT_COLOR_BIT);
        shownPixels = GpuBuffer.hostVisible(device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        port.add(new Capture(color, Keys.SCENE_COLOR, width, height));
        if (look == Look.FILM) {
            AutoExposure.SLOT.choose(settings, AutoExposure.HISTOGRAM);
            port.add(new Bloom()).add(new AutoExposure(fireworks::time));
        }
        port.add(new DisplayTransform().transform(Keys.SCENE_COLOR, SHOWN, DisplayEncoding.SDR_SRGB))
            .add(new Capture(shownPixels, SHOWN, width, height))
            .output(SHOWN, new com.truesim.engine.graph.ExternalImage("shown", width, height, shownFormat,
                shownUsage, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .set(shown.handle(), shown.view()));
        graph.add(new ParticleSimulation(particles));
        poolCopy = GpuBuffer.hostVisible(device, particles.pool().buffer().size(), VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        int usage = org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        probe = new com.truesim.engine.vulkan.GpuImage(device, 4, 4, org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM,
            usage, VK_IMAGE_ASPECT_COLOR_BIT);
        if (look == Look.LINEAR) {
            graph.add(new Probe(poolCopy)).output(PROBE, new com.truesim.engine.graph.ExternalImage("probe", 4, 4,
                Format.R8G8B8A8_UNORM, usage, org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(probe.handle(), probe.view()));
        }
    }

    private static final Key<Image> PROBE = Key.image("test.fireworksProbe");
    final GpuBuffer poolCopy;
    private final com.truesim.engine.vulkan.GpuImage probe;

    /** Float {@code field} of particle {@code i} of the range at slot {@code first}, as the last frame left it. */
    float particle(int first, int i, long field) {
        long at = ((long) particles.currentHalf() * particles.pool().slots() + first + i)
            * com.truesim.effects.particles.layout.ParticleLayout.BYTES;
        return poolCopy.memory().get(ValueLayout.JAVA_FLOAT, at + field);
    }

    /** Int {@code field} of particle {@code i} of the range at slot {@code first}. */
    int particleInt(int first, int i, long field) {
        long at = ((long) particles.currentHalf() * particles.pool().slots() + first + i)
            * com.truesim.effects.particles.layout.ParticleLayout.BYTES;
        return poolCopy.memory().get(ValueLayout.JAVA_INT, at + field);
    }

    /** The bytes of particles [0, count) of the range at slot {@code first}. */
    byte[] particleBytes(int first, int count) {
        long at = ((long) particles.currentHalf() * particles.pool().slots() + first)
            * com.truesim.effects.particles.layout.ParticleLayout.BYTES;
        return poolCopy.memory().asSlice(at, (long) count
            * com.truesim.effects.particles.layout.ParticleLayout.BYTES).toArray(ValueLayout.JAVA_BYTE);
    }

    /** Copies the pool into a host buffer each frame, after the simulation. */
    private record Probe(GpuBuffer pool) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(PROBE);
        }

        @Override
        public void build(GraphBuilder g) {
            com.truesim.engine.graph.Buffer particles = g.require(com.truesim.effects.particles.ParticleKeys.PARTICLES);
            Image image = g.create("probe", com.truesim.engine.graph.ImageDesc.of(Format.R8G8B8A8_UNORM, 4, 4));
            g.pass("fireworks pool probe").read(particles, com.truesim.engine.graph.BufferAccess.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).sideEffects().record(ctx -> {
                    ctx.clear(image.mip(0), 0, 0, 0, 1);
                    com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    try (var stack = stackPush()) {
                        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack)
                            .size(pool.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(particles),
                            pool.handle(), region);
                    }
                });
            g.provide(PROBE, image);
        }
    }

    private int add(MeshData mesh) {
        try (mesh) {
            return pool.add(mesh);
        }
    }

    /** A material of base colour (r, g, b), rough, glowing {@code emissive} cd/m² (times the colour given). */
    int material(float r, float g, float b, float roughness, float er, float eg, float eb) {
        return materials.add(new Material(MetallicRoughnessParameters.DEFAULTS.withBaseColor(new Color(r, g, b, 1))
            .withRoughness(roughness).withMetallic(0).withEmissive(new Color(er, eg, eb, 1))));
    }

    /** An instance of {@code mesh} with {@code material} at (x, y, z), turned by yaw radians, half extents s. */
    void place(int mesh, int material, double x, double y, double z, double yaw, double rollZ, float sx, float sy,
        float sz) {
        double cy = Math.cos(yaw / 2);
        double sy2 = Math.sin(yaw / 2);
        double cz = Math.cos(rollZ / 2);
        double sz2 = Math.sin(rollZ / 2);
        // yaw about y, then roll about z: q = q_yaw * q_roll
        double qx = sy2 * sz2;
        double qy = sy2 * cz;
        double qz = cy * sz2;
        double qw = cy * cz;
        gpu.set(instances++, mesh, material, x, y, z, (float) qx, (float) qy, (float) qz, (float) qw, sx, sy, sz);
    }

    /** The camera at (x, y, z) looking towards (tx, ty, tz), {@code fov} degrees vertically. */
    void look(double x, double y, double z, double tx, double ty, double tz, double fov) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        camera.position(x, y, z).set(dx / l, dy / l, dz / l, 0, 1, 0, Math.toRadians(fov), width, height, 0.1);
    }

    /** The camera at (x, y, z) looking straight down, -z up in the image, {@code fov} degrees. */
    void lookDown(double x, double y, double z, double fov) {
        camera.position(x, y, z).set(0, -1, 0, 0, 0, -1, Math.toRadians(fov), width, height, 0.1);
    }

    /**
     * A frame as an application runs it: the shows, the scene and its lights, then the effects synced and the graph
     * recorded; {@code seconds} of the world's time.
     */
    void frame(double seconds) {
        fireworks.update(seconds);
        scene.update();
        lightSync.update();
        graph.beginFrame();
        fx.update(seconds);
        view.set(1, MeshletRenderer.MODE_LIT);
        graph.submit(0, 0);
    }

    /** {@code count} frames of {@code seconds}, then the GPU waited for. */
    void frames(int count, double seconds) {
        for (int i = 0; i < count; i++) {
            frame(seconds);
        }
        device.waitIdle();
    }

    /** The last frame's scene colour at (x, y): red, green, blue, cd/m². */
    float[] color(int x, int y) {
        long at = 8L * (y * (long) width + x);
        MemorySegment m = color.memory();
        return new float[] {Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at)),
            Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 2)),
            Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 4))};
    }

    /** Validation errors so far (0 without validation). */
    int validationErrors() {
        return instance.validationErrors();
    }

    @Override
    public void close() {
        device.waitIdle();
        fireworks.close();
        graph.close();
        poolCopy.close();
        probe.close();
        particles.close();
        shown.close();
        shownPixels.close();
        color.close();
        lights.close();
        textures.close();
        materials.close();
        renderer.close();
        gpu.close();
        pool.close();
        device.close();
        instance.close();
    }

    /** Copies {@code key}'s image into a host buffer each frame: the scene colour before the post effects. */
    private record Capture(GpuBuffer pixels, Key<Image> key, int width, int height) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(key);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image image = g.modify(key);
            g.pass("fireworks capture").read(image, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
                try (var stack = stackPush()) {
                    VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                    region.bufferRowLength(width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .layerCount(1);
                    region.imageExtent().set(width, height, 1);
                    org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(image),
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
                }
            });
            g.provide(key, image);
        }
    }

    /** The last frame as shown (the display transform's output), waited for. */
    java.awt.image.BufferedImage shot() {
        device.waitIdle();
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height,
            java.awt.image.BufferedImage.TYPE_INT_RGB);
        MemorySegment m = shownPixels.memory();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                long at = 8L * (y * (long) width + x);
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    float v = Float.float16ToFloat(m.get(ValueLayout.JAVA_SHORT_UNALIGNED, at + 2L * c));
                    rgb = rgb << 8 | Math.round(255 * Math.max(0, Math.min(1, v)));
                }
                row[x] = rgb;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    /**
     * A clear moonless night over the Alps as a panorama (the engine's mapping: u from atan2(z, x), v from the
     * zenith), in cd/m²: the sky's airglow, a little brighter towards the horizon, and the village's glow along it; a
     * faint band of the Milky Way; 6,000 stars to about magnitude 6.5, each a texel whose luminance gives its
     * illuminance (a magnitude 0 star 2.5 µlx) over the texel's solid angle; a dark ground.
     */
    static MemorySegment nightSky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        float[] sky = new float[3 * width * height];
        double[] pole = {0.3, 0.75, -0.6}; // the Milky Way's pole, roughly
        double pl = Math.sqrt(pole[0] * pole[0] + pole[1] * pole[1] + pole[2] * pole[2]);
        for (int y = 0; y < height; y++) {
            double theta = Math.PI * (y + 0.5) / height;
            for (int x = 0; x < width; x++) {
                double phi = 2 * Math.PI * ((x + 0.5) / width - 0.5);
                double dx = Math.sin(theta) * Math.cos(phi);
                double dy = Math.cos(theta);
                double dz = Math.sin(theta) * Math.sin(phi);
                int i = 3 * (y * width + x);
                if (dy < 0) {
                    sky[i] = 2e-4f;
                    sky[i + 1] = 2e-4f;
                    sky[i + 2] = 2.2e-4f;
                    continue;
                }
                double horizon = Math.pow(1 - dy, 6);
                double band = Math.abs(dx * pole[0] + dy * pole[1] + dz * pole[2]) / pl;
                double milky = 6e-4 * Math.exp(-band * band / (2 * 0.12 * 0.12)) * (0.6 + 0.4 * Math.sin(phi * 3));
                sky[i] = (float) (5e-4 + 3e-3 * horizon * 1.4 + milky);
                sky[i + 1] = (float) (6e-4 + 3e-3 * horizon * 1.0 + milky);
                sky[i + 2] = (float) (1.1e-3 + 3e-3 * horizon * 0.7 + milky * 1.1);
            }
        }
        SplittableRandom random = new SplittableRandom(1969);
        double texel = (2 * Math.PI / width) * (Math.PI / height);
        for (int s = 0; s < 6000; s++) {
            // magnitudes as the sky's counts rise, ~ 10^(0.5 m): uniform in 10^(0.5 m) up to 6.5
            double m = 2 * Math.log10(random.nextDouble(Math.pow(10, 0.5 * -1), Math.pow(10, 0.5 * 6.5)));
            double y = random.nextDouble(-0.05, 1);
            double phi = random.nextDouble(2 * Math.PI) - Math.PI;
            int py = (int) (Math.acos(y) / Math.PI * height);
            int px = (int) ((phi / (2 * Math.PI) + 0.5) * width);
            double theta = Math.PI * (py + 0.5) / height;
            double lux = 2.5e-6 * Math.pow(10, -0.4 * m);
            double l = lux / (texel * Math.max(Math.sin(theta), 1e-3));
            double t = random.nextDouble(); // colour: from blue-white to orange
            int i = 3 * (Math.min(py, height - 1) * width + Math.min(px, width - 1));
            sky[i] += (float) (l * (0.8 + 0.4 * t));
            sky[i + 1] += (float) (l * 0.95);
            sky[i + 2] += (float) (l * (1.25 - 0.45 * t));
        }
        for (int p = 0; p < width * height; p++) {
            long at = 8L * p;
            for (int k = 0; k < 3; k++) {
                texels.set(half, at + 2L * k, Float.floatToFloat16(sky[3 * p + k]));
            }
            texels.set(half, at + 6, Float.floatToFloat16(1));
        }
        return texels;
    }
}
