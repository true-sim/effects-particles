package com.truesim.effects.particles;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.common.jobs.TaskScheduler;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.Textures;
import com.truesim.engine.scene.SceneResources;
import com.truesim.scene.assets.AssetCache;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.assets.Generator;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.effects.EffectAssets;
import com.truesim.scene.effects.VectorFieldData;
import com.truesim.scene.render.RenderAssets;
import com.truesim.scene.render.TextureData;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.List;

/**
 * The content tests' effects name, as a game's comes: generated assets in an asset cache, put on the GPU by scene
 * resources the particles resolve through ({@link Particles#assets}). Content held up front is on the GPU before the
 * first frame, so a test's first frames draw with it.
 */
final class TestAssets implements AutoCloseable {

    /** A row of texels, one int (RGBA, red in the low byte) each. */
    record Row(List<Integer> rgba, boolean srgb) {
    }

    /** A vector field of {@code size} cells a side, every cell {@code vector}. */
    record Field(int size, float x, float y, float z) {
    }

    static final Generator<Row, TextureData> ROWS = (row, seed) -> {
        MemorySegment texels = Arena.ofAuto().allocate(4L * row.rgba().size());
        for (int i = 0; i < row.rgba().size(); i++) {
            texels.setAtIndex(ValueLayout.JAVA_INT, i, row.rgba().get(i));
        }
        return new TextureData(row.rgba().size(), 1, row.srgb() ? TextureData.ColorSpace.SRGB
            : TextureData.ColorSpace.LINEAR, texels);
    };
    static final Generator<Field, VectorFieldData> FIELDS = (field, seed) -> {
        float[] vectors = new float[3 * field.size() * field.size() * field.size()];
        for (int i = 0; i < vectors.length; i += 3) {
            vectors[i] = field.x();
            vectors[i + 1] = field.y();
            vectors[i + 2] = field.z();
        }
        return new VectorFieldData(field.size(), field.size(), field.size(), vectors);
    };

    private final TaskScheduler tasks = new TaskScheduler();
    private final AssetCache cache = new AssetCache(tasks);
    private final SceneResources resources;

    /** Resources putting content into {@code pool}, {@code materials} and {@code textures}. */
    TestAssets(GeometryPool pool, Materials materials, Textures textures) {
        resources = new SceneResources(cache, pool, materials, textures);
    }

    /** A color texture (sRGB) of one row of {@code rgba} texels. */
    static AssetRef<TextureData> color(int... rgba) {
        return AssetRef.generated(RenderAssets.TEXTURE, ROWS, new Row(java.util.Arrays.stream(rgba).boxed().toList(),
            true), 1);
    }

    /** A data texture (linear) of one row of {@code rgba} texels. */
    static AssetRef<TextureData> data(int... rgba) {
        return AssetRef.generated(RenderAssets.TEXTURE, ROWS, new Row(java.util.Arrays.stream(rgba).boxed().toList(),
            false), 1);
    }

    /** A vector field of {@code size} cells a side, every one ({@code x}, {@code y}, {@code z}) m/s². */
    static AssetRef<VectorFieldData> field(int size, float x, float y, float z) {
        return AssetRef.generated(EffectAssets.VECTOR_FIELD, FIELDS, new Field(size, x, y, z), 1);
    }

    /** A mesh made by {@code make}, as an asset. */
    static AssetRef<MeshData> mesh(String name, java.util.function.Supplier<MeshData> make) {
        return AssetRef.generated(RenderAssets.MESH, (String n, long seed) -> make.get(), name, 1);
    }

    SceneResources resources() {
        return resources;
    }

    AssetCache cache() {
        return cache;
    }

    /** Holds {@code refs} and waits until they are on the GPU. */
    TestAssets preload(AssetRef<?>... refs) {
        for (AssetRef<?> ref : refs) {
            resources.hold(ref);
        }
        return await();
    }

    /** Waits until everything held is on the GPU: the loads handed over and uploaded. */
    TestAssets await() {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            tasks.drainCompleted();
            Thread.onSpinWait();
        } while (resources.loadingCount() > 0 && System.nanoTime() < deadline);
        tasks.drainCompleted();
        assertThat(resources.loadingCount()).as("content still loading").isZero();
        return this;
    }

    @Override
    public void close() {
        resources.close();
        cache.close();
        tasks.close();
    }
}
