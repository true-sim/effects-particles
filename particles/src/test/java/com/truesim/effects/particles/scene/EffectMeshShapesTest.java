package com.truesim.effects.particles.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.common.state.ChangeClock;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.scene.SceneSync;
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
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.Renderable;
import com.truesim.scene.render.SkinBinding;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mesh and bone shapes over a scene's renderables, as the scene sync draws them: a skinned renderable's posed copy
 * and a chain of joints' nodes ({@link EffectSync#meshes}, {@link EffectSync#joints}). Validated.
 */
class EffectMeshShapesTest {

    private static final int INSTANCES = 4;
    static final AssetRef<MeshData> SKINNED = AssetRef.generated(RenderAssets.MESH,
        (Generator<Integer, MeshData>) (unused, seed) -> skinnedSquare(), 0, 1);
    static final AssetRef<MaterialData> RED = AssetRef.generated(RenderAssets.MATERIAL,
        (Generator<Integer, MaterialData>) (unused, seed) -> MaterialData.builder(MaterialData.METALLIC_ROUGHNESS)
            .set("baseColor", 0f, 0f, 0f, 1f).set("emissive", 1f, 0f, 0f).build(), 0, 1);

    private VulkanInstance instance;
    private VulkanDevice device;
    private final FrameSlots slots = new FrameSlots(2);
    private GeometryPool pool;
    private Materials materials;
    private Textures textures;
    private TaskScheduler tasks;
    private AssetCache assets;
    private SceneResources resources;
    private final List<AutoCloseable> closing = new ArrayList<>();

    @BeforeEach
    void start() {
        try {
            instance = VulkanInstance.builder().applicationName("tests").validation(true)
                .synchronizationValidation(true).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        assumeThat(instance.gpus()).as("no Vulkan 1.3 GPU").isNotEmpty();
        device = VulkanDevice.createHeadless(instance.bestGpu());
        pool = new GeometryPool(device, GeometryPool.Capacity.of(1 << 10, 16, 4), slots);
        materials = new Materials(device, 16, slots);
        textures = new Textures(device, slots);
        tasks = new TaskScheduler();
        assets = new AssetCache(tasks);
        resources = new SceneResources(assets, pool, materials, textures);
    }

    @AfterEach
    void stop() throws Exception {
        if (device != null) {
            device.waitIdle();
            int errors = instance.validationErrors();
            for (int i = closing.size() - 1; i >= 0; i--) {
                closing.get(i).close();
            }
            resources.close();
            assets.close();
            tasks.close();
            textures.close();
            materials.close();
            pool.close();
            device.close();
            instance.close();
            assertThat(errors).as("validation errors").isZero();
        } else if (instance != null) {
            instance.close();
        }
    }

    /** A 2 x 2 square facing up whose left half joint 0 moves and right half joint 1. */
    static MeshData skinnedSquare() {
        float[] positions = {-1, 0, -1, 1, 0, -1, -1, 0, 1, 1, 0, 1};
        float[] normals = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
        float[] weights = {1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0};
        int[] joints = {0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL, VertexAttribute.JOINTS,
            VertexAttribute.WEIGHTS), 4).positions(positions).attribute(VertexAttribute.NORMAL, normals)
            .attribute(VertexAttribute.WEIGHTS, weights).joints(joints).submesh(new int[] {0, 2, 1, 1, 2, 3})
            .buildFlat();
    }

    /** Lets the background loads finish, at frame boundaries. */
    private void load() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (assets.loadingCount() > 0 || tasks.pending() > 0) {
            assertThat(System.nanoTime()).as("loads finishing").isLessThan(deadline);
            Thread.sleep(1);
            tasks.drainCompleted();
        }
    }

    private <T extends AutoCloseable> T closing(T closeable) {
        closing.add(closeable);
        return closeable;
    }

    private GpuScene gpu() {
        return closing(new GpuScene(device, pool, INSTANCES, slots));
    }

    /** Copies the particles' emitter state into {@code state} each frame, after the simulation. */
    private record ParticleState(GpuBuffer state) implements Technique {
        static final Key<Image> SEEN = Key.image("test.particleState");

        @Override
        public List<Key<?>> provides() {
            return List.of(SEEN);
        }

        @Override
        public void build(GraphBuilder g) {
            com.truesim.engine.graph.Buffer states = g.require(com.truesim.effects.particles.ParticleKeys.STATE);
            Image image = g.create("particle state", com.truesim.engine.graph.ImageDesc.of(Format.R8G8B8A8_UNORM, 4,
                4));
            g.pass("particle state").read(states, com.truesim.engine.graph.BufferAccess.TRANSFER_SRC)
                .write(image, Access.TRANSFER_DST).sideEffects().record(ctx -> {
                    ctx.clear(image.mip(0), 0, 0, 0, 1);
                    // Every frame copies into the same buffer: after the frames before
                    com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                    try (var stack = stackPush()) {
                        org.lwjgl.vulkan.VkBufferCopy.Buffer region = org.lwjgl.vulkan.VkBufferCopy.calloc(1, stack);
                        region.size(state.size());
                        org.lwjgl.vulkan.VK10.vkCmdCopyBuffer(ctx.unsafe().commands(), ctx.unsafe().buffer(states),
                            state.handle(), region);
                    }
                });
            g.provide(SEEN, image);
        }
    }

    /** The {@code corner}-th word of the box of emitter instance {@code instance}'s particles: min x, y, z, max. */
    private static float particleBound(GpuBuffer state, int instance, int corner) {
        int word = state.memory().getAtIndex(ValueLayout.JAVA_INT, (long) instance
            * com.truesim.effects.particles.layout.ParticleConstants.STATE_WORDS
            + com.truesim.effects.particles.layout.ParticleConstants.STATE_BOUNDS + corner) ^ 0x80000000;
        return Float.intBitsToFloat(word >= 0 ? word : word ^ 0x7fffffff);
    }

    @Test
    void meshShapesEmitFromSkinnedRenderablesAsPosedAndBoneShapesAlongJoints() throws InterruptedException {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        ComponentStore<Renderable> renderables = scene.components().store(Renderable.TYPE);
        SceneSync sync = closing(new SceneSync(resources, scene, gpu()));
        FrameGraph graph = closing(new FrameGraph(device, slots));
        MeshletRenderer renderer = closing(new MeshletRenderer(device, sync.gpu(), VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_D32_SFLOAT, slots));
        com.truesim.effects.particles.Particles particles = closing(
            new com.truesim.effects.particles.Particles(device, slots, 1 << 20, 8).meshes(renderer));
        GpuBuffer state = closing(GpuBuffer.hostVisible(device, 4L
            * com.truesim.effects.particles.layout.ParticleConstants.STATE_WORDS * 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
        int usage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        GpuImage target = closing(new GpuImage(device, 4, 4, VK_FORMAT_R8G8B8A8_UNORM, usage,
            VK_IMAGE_ASPECT_COLOR_BIT));
        graph.add(new com.truesim.effects.particles.ParticleSimulation(particles)).add(new ParticleState(state))
            .output(ParticleState.SEEN, new ExternalImage("state", 4, 4, Format.R8G8B8A8_UNORM, usage,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view()));
        long body = scene.create();
        scene.setTranslation(body, 5, 1, -2);
        long left = scene.create(body);
        long right = scene.create(body);
        float[] identities = new float[32];
        for (int i = 0; i < 4; i++) {
            identities[5 * i] = 1;
            identities[16 + 5 * i] = 1;
        }
        renderables.set(body, Renderable.of(SKINNED, List.of(RED)));
        Renderable.setSkin(scene, body, new SkinBinding(new long[] {left, right}, identities));
        com.truesim.scene.effects.Effects effects = new com.truesim.scene.effects.Effects(scene);
        EffectSync fx = new EffectSync(effects, particles).meshes(sync);
        // Short-lived particles, many: their box is where they were born lately
        com.truesim.scene.effects.EffectData burning = com.truesim.scene.effects.EffectData.builder()
            .emitter("skin", e -> e.capacity(400).spawn(com.truesim.scene.effects.Spawn.rate(2000))
                .init(com.truesim.scene.effects.Shape.mesh(com.truesim.scene.effects.Shape.SURFACE),
                    com.truesim.scene.effects.Init.lifetime(0.1f), com.truesim.scene.effects.Init.size(
                    com.truesim.scene.effects.Bind.constant(0.02f))))
            .emitter("bones", e -> e.capacity(400).spawn(com.truesim.scene.effects.Spawn.rate(2000))
                .init(com.truesim.scene.effects.Shape.bones(0), com.truesim.scene.effects.Init.lifetime(0.1f),
                    com.truesim.scene.effects.Init.size(com.truesim.scene.effects.Bind.constant(0.02f))))
            .build();
        com.truesim.scene.effects.Effect playing = effects.play(burning, body);
        long effect = playing.id();
        scene.setTranslation(left, -1, 0, 0);
        scene.setTranslation(right, 1, 0, 0); // the square moves sideways by its joints: its halves 2 apart
        int[] frames = {0};
        Runnable frame = () -> {
            int slot = graph.beginFrame();
            slots.begin(slot);
            tasks.drainCompleted();
            scene.update();
            sync.update();
            if (frames[0]++ == 1) {
                fx.joints(effect, 1, left, right);
            }
            fx.update(1 / 60.0);
            graph.submit(0, 0);
            slots.end();
        };
        for (int round = 0; round < 3; round++) {
            frame.run();
            load();
        }
        for (int f = 0; f < 12; f++) {
            frame.run();
        }
        device.waitIdle();
        assertThat(fx.jointChainCount()).isOne();
        int skin = particles.instance(effect, 0);
        int bones = particles.instance(effect, 1);
        // The posed square: its left half 1 left, its right half 1 right, so from -2 to 2 across (y 0, z -1 to 1)
        assertThat(particleBound(state, skin, 0)).isCloseTo(-2, within(0.05f));
        assertThat(particleBound(state, skin, 3)).isCloseTo(2, within(0.05f));
        assertThat(particleBound(state, skin, 1)).isCloseTo(0, within(0.05f));
        assertThat(particleBound(state, skin, 4)).isCloseTo(0, within(0.05f));
        // Along the joints: from x -1 to 1, on the body's axis
        assertThat(particleBound(state, bones, 0)).isCloseTo(-1, within(0.05f));
        assertThat(particleBound(state, bones, 3)).isCloseTo(1, within(0.05f));
        assertThat(particleBound(state, bones, 2)).isCloseTo(0, within(0.05f));
        assertThat(particleBound(state, bones, 5)).isCloseTo(0, within(0.05f));
        assertThatIllegalArgumentException().isThrownBy(() -> fx.joints(effect, 1, new long[17]));
        assertThatIllegalArgumentException().isThrownBy(() -> fx.joints(effect, 1, 12345L));
        assertThatIllegalArgumentException().isThrownBy(() -> fx.joints(12345L, 1, left));
        fx.joints(effect, 1, right, left); // set again: the same chain
        assertThat(fx.jointChainCount()).isOne();
        playing.kill();
        frame.run();
        frame.run();
        assertThat(fx.jointChainCount()).as("ended: let go").isZero();
    }
}
