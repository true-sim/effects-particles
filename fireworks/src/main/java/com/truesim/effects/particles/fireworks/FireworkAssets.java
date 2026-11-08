package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.assets.AssetInput;
import com.truesim.scene.assets.AssetLoader;
import com.truesim.scene.assets.AssetType;
import java.io.IOException;
import java.io.InputStream;

/**
 * The fireworks' asset type: shows ({@link FireworkShow}), loaded through the asset cache from {@code .fireworks}
 * files by {@link #IMPORTER} ({@code paths.importers().add(FireworkAssets.SHOW, "fireworks", FireworkAssets.IMPORTER)},
 * which {@link FireworksPlugin} does with the assets plugin installed).
 */
public final class FireworkAssets {

    /** Fireworks shows: timelines of shells and a seed. */
    public static final AssetType<FireworkShow> SHOW = AssetType.of("fireworksShow", FireworkShow.class,
        FireworkShow::bytes);

    /** The {@code .fireworks} importer: it keeps nothing. */
    public static final AssetLoader<FireworkShow> IMPORTER = new AssetLoader<>() {
        @Override
        @AllowAllocation("importing shows")
        public FireworkShow load(AssetInput input) throws IOException {
            try (InputStream in = input.open()) {
                String what = input.path() != null ? input.path().toString() : input.id().toString();
                try {
                    return FireworkShow.parse(in.readAllBytes(), what);
                } catch (IllegalArgumentException e) {
                    throw new IOException(e.getMessage(), e);
                }
            }
        }
    };

    private FireworkAssets() {
    }
}
