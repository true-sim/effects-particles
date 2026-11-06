package com.truesim.effects.particles;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.particles.layout.ParticleConstants;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.scene.SceneResources;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Retiring;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.scene.assets.AssetRef;
import com.truesim.scene.effects.EffectAssets;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.VectorFieldData;
import com.truesim.scene.render.MaterialData;
import com.truesim.scene.render.RenderAssets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The content effects name, through the scene's resources (core prerequisite 7.5): the textures, meshes and vector
 * fields renderers and modules name are held while an effect naming them is encoded, loaded through the asset cache
 * and found by ref where they are on the GPU. Without resources every texture draws as none (a round sprite) and mesh
 * renderers draw nothing. Vector fields ({@link EffectAssets#VECTOR_FIELD}) become 3D textures through the uploader
 * registered with the resources ({@link VectorField}). Used on the thread effects update on.
 */
final class ParticleAssets {

    private static final Logger LOG = LoggerFactory.getLogger(ParticleAssets.class);

    private final VulkanDevice device;
    private final Retiring retired;
    private SceneResources resources;
    private GpuScene scene; // whose materials mesh particles take, or null
    // The meshes mesh renderers name, by mesh and then material
    private final Map<AssetRef<?>, Map<AssetRef<?>, Mesh>> meshes = new HashMap<>();
    private final Map<Integer, VectorField> fields = new HashMap<>();

    /** A mesh and the material mesh renderers draw it with: its pool mesh and block of material ids once there. */
    static final class Mesh {
        final AssetRef<?> mesh;
        final AssetRef<?> material;
        int users;
        int poolMesh = -1;
        int materials = -1; // the first of its block of material ids, held
        int materialSlots;
        long seen = -1; // the residency it was resolved at

        Mesh(AssetRef<?> mesh, AssetRef<?> material) {
            this.mesh = mesh;
            this.material = material;
        }
    }

    ParticleAssets(VulkanDevice device, FrameSlots slots) {
        this.device = device;
        this.retired = new Retiring(slots);
    }

    /**
     * Resolves through {@code resources} from now on, registering the vector field uploader with them unless another
     * particle system did; set before effects play.
     */
    @AllowAllocation("set up once")
    void resources(SceneResources resources) {
        this.resources = resources;
        if (resources == null) {
            return;
        }
        try {
            resources.sampled(EffectAssets.VECTOR_FIELD, new SceneResources.SampledUploader<>() {
                @Override
                @AllowAllocation("a vector field arrived")
                public int upload(VectorFieldData data) {
                    VectorField field = VectorField.of(device, data.width(), data.height(), data.depth(),
                        data.vectors());
                    fields.put(field.slot(), field);
                    return field.slot();
                }

                @Override
                @AllowAllocation("a vector field let go of")
                public void remove(int slot) {
                    VectorField field = fields.remove(slot);
                    if (field != null) {
                        retired.add(field); // frames in flight sample it
                    }
                }
            });
        } catch (IllegalStateException e) {
            LOG.debug("Vector fields of these resources are uploaded by another particle system already");
        }
    }

    SceneResources resources() {
        return resources;
    }

    /** Mesh particles take materials of {@code scene} from now on (null: none). */
    void scene(GpuScene scene) {
        this.scene = scene;
    }

    /** Holds {@code ref}, a texture, mesh or vector field an effect names; nothing without resources. */
    @AllowAllocation("an effect encoded")
    void hold(AssetRef<?> ref) {
        if (resources == null || ref == null || ref == Forces.NO_FIELD) {
            return;
        }
        try {
            resources.hold(ref);
        } catch (RuntimeException e) {
            LOG.warn("Effects name {}, which cannot be held: drawn without it", ref, e);
        }
    }

    /** Lets go of {@code ref}, held by {@link #hold}. */
    void release(AssetRef<?> ref) {
        if (resources == null || ref == null || ref == Forces.NO_FIELD) {
            return;
        }
        try {
            resources.release(ref);
        } catch (IllegalStateException e) {
            // it could not be held: nothing to let go of
        }
    }

    /** {@code ref}'s sampled slot, or {@code NO_TEXTURE}. Allocates nothing. */
    int texture(AssetRef<?> ref) {
        int slot = resources == null || ref == null ? -1 : resources.texture(ref);
        return slot < 0 ? ParticleConstants.NO_TEXTURE : slot;
    }

    /** A count that changes whenever what {@link #texture} and {@link #mesh} say may have. */
    long residency() {
        return resources == null ? 0 : resources.residency();
    }

    /** The mesh {@code mesh} drawn with {@code material} (either may be null), held for one more user. */
    @AllowAllocation("an effect encoded")
    Mesh acquireMesh(AssetRef<?> mesh, AssetRef<?> material) {
        if (mesh == null) {
            return null;
        }
        Mesh entry = meshes.computeIfAbsent(mesh, m -> new HashMap<>()).get(material);
        if (entry == null) {
            entry = new Mesh(mesh, material);
            meshes.get(mesh).put(material, entry);
            hold(mesh);
        }
        entry.users++;
        return entry;
    }

    /** Lets go of {@code entry} for one user: at none, its mesh and materials are released. */
    void releaseMesh(Mesh entry) {
        if (entry == null || --entry.users > 0) {
            return;
        }
        Map<AssetRef<?>, Mesh> byMaterial = meshes.get(entry.mesh);
        byMaterial.remove(entry.material);
        if (byMaterial.isEmpty()) {
            meshes.remove(entry.mesh);
        }
        if (entry.materials >= 0) {
            resources.releaseMaterials(entry.materials);
        }
        release(entry.mesh);
    }

    /**
     * Brings {@code entry} up to date with the resources: its pool mesh and, once it is there, its block of material
     * ids (the scene told to draw them). Allocates nothing unless the mesh just arrived.
     */
    void resolve(Mesh entry) {
        if (resources == null || entry.seen == resources.residency()) {
            return;
        }
        entry.seen = resources.residency();
        int mesh = resources.mesh(entry.mesh);
        if (mesh != entry.poolMesh && entry.materials >= 0) {
            resources.releaseMaterials(entry.materials); // the mesh left or changed: its submeshes may too
            entry.materials = -1;
        }
        entry.poolMesh = mesh;
        if (mesh >= 0 && entry.materials < 0) {
            holdMaterials(entry);
        }
    }

    @AllowAllocation("a mesh particles take arrived")
    @SuppressWarnings("unchecked")
    private void holdMaterials(Mesh entry) {
        entry.materialSlots = Math.max(1, resources.pool().materialSlots(entry.poolMesh));
        List<AssetRef<MaterialData>> list = entry.material != null && entry.material.type() == RenderAssets.MATERIAL
            ? List.of((AssetRef<MaterialData>) entry.material) : List.of();
        entry.materials = resources.holdMaterials(list, entry.materialSlots);
        if (scene != null) {
            scene.reference(entry.materials, entry.materialSlots);
        }
    }

    /** Has the scene draw every mesh particle's materials (a scene set after meshes arrived). */
    void referenceAll() {
        if (scene == null) {
            return;
        }
        for (Map<AssetRef<?>, Mesh> byMaterial : meshes.values()) {
            for (Mesh entry : byMaterial.values()) {
                if (entry.materials >= 0) {
                    scene.reference(entry.materials, entry.materialSlots);
                }
            }
        }
    }

    /**
     * Frees the vector fields no frame reads any more, and writes the blocks of material ids whose materials came
     * (mesh particles' materials drawn as the default until they load); once per frame.
     */
    void collect() {
        retired.release();
        if (resources != null) {
            resources.flush();
        }
    }

    /** Frees what is left: after the GPU is idle. */
    void close() {
        retired.close();
        for (VectorField field : fields.values()) {
            field.close();
        }
        fields.clear();
    }
}
