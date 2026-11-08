package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.particles.scene.EffectsPlugin;
import com.truesim.engine.app.Frame;
import com.truesim.engine.app.Plugin;
import com.truesim.engine.app.PluginKey;
import com.truesim.engine.app.Stage;
import com.truesim.engine.app.Startup;
import com.truesim.engine.scene.Assets;
import com.truesim.engine.settings.EngineSettings;
import java.util.List;

/**
 * Fireworks as a plugin over the effects plugin: the particle kernel with the fireworks' modules
 * ({@link FireworkModules}), the fireworks' settings in the registry, {@code .fireworks} shows importing through the
 * assets plugin's cache when it is installed, and the world's {@link FireworkDisplay} components played each frame
 * ({@link FireworksSync}) in the late update, before the effects sync.
 *
 * <pre>{@code
 * app.use(new Rendering(), new Assets().mount("game", Path.of("assets")), new EffectsPlugin(), new FireworksPlugin());
 * EffectsPlugin fx = engine.get(EffectsPlugin.KEY);
 * Effects effects = fx.effects(scene);
 * FireworksSync fireworks = engine.get(FireworksPlugin.KEY).play(effects);
 * scene.components().set(square, FireworkDisplay.TYPE, FireworkDisplay.of(cache.get(showRef)));
 * fireworks.sounds().forEachSince(heard, audio);                 // launches, bursts and crackles for the audio
 * }</pre>
 */
public final class FireworksPlugin implements Plugin {

    /** Its key. */
    public static final PluginKey<FireworksPlugin> KEY = PluginKey.of("fireworks", FireworksPlugin.class);

    private EngineSettings settings;
    private FireworksSync sync;

    @Override
    public PluginKey<FireworksPlugin> key() {
        return KEY;
    }

    @Override
    public List<PluginKey<?>> requires() {
        return List.of(EffectsPlugin.KEY);
    }

    @Override
    public List<PluginKey<?>> after() {
        return List.of(Assets.KEY);
    }

    @Override
    @AllowAllocation("starting up")
    public void start(Stage stage, Startup startup) {
        if (stage == Stage.BEFORE_INSTANCE) {
            settings = startup.settings();
            FireworksSettings.register(startup.registry());
        } else if (stage == Stage.DEVICE) {
            startup.get(EffectsPlugin.KEY).particles().modules(FireworkModules.MODULES);
            startup.find(Assets.KEY).ifPresent(assets ->
                assets.paths().importers().add(FireworkAssets.SHOW, "fireworks", FireworkAssets.IMPORTER));
        }
    }

    /** Plays the fireworks shows of {@code effects}' scene from now on; once. */
    @AllowAllocation("set up once")
    public FireworksSync play(com.truesim.scene.effects.Effects effects) {
        if (sync != null) {
            throw new IllegalStateException("The fireworks plugin plays one world's shows already");
        }
        sync = new FireworksSync(effects, settings);
        return sync;
    }

    /** The world's shows ({@link #play}). */
    public FireworksSync sync() {
        if (sync == null) {
            throw new IllegalStateException("No world's shows yet: play(effects)");
        }
        return sync;
    }

    @Override
    public void lateUpdate(Frame frame) {
        if (sync != null) {
            sync.update(frame.delta());
        }
    }

    @Override
    public void stop() {
        if (sync != null) {
            sync.close();
            sync = null;
        }
    }
}
