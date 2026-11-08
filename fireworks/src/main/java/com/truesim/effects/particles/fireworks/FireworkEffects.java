package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.EffectType;
import com.truesim.scene.effects.EmitterData;
import com.truesim.scene.effects.FloatCurve;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Over;
import com.truesim.scene.effects.ParticleEvent;
import com.truesim.scene.effects.Renderer;
import com.truesim.scene.effects.Shape;
import com.truesim.scene.effects.Spawn;
import java.util.HashMap;
import java.util.Map;

/**
 * A shell's two effects, made from its {@link ShellDesign} and the settings, as scene-effects' data for the GPU
 * particles: the lift (the shell rising with its tail of sparks and smoke, the mortar's flash and smoke) and the burst
 * (its stars by pattern, their tails, splits, strobes and crackles, the bursting charge's flash and the smoke they
 * leave). Stars' colours are luminances (cd/m², times the luminance scale) of their colorant's colour over a round
 * sprite of the glow's size giving the star's intensity ({@link ShellDesign#spriteLuminance}); every particle moves by
 * gravity and the air's drag through the world's wind ({@code force.wind}: a drag towards it, no gusts), as
 * {@link Ballistics} has it. What changes per shell fired (its velocity, fuse, colours, the pattern's turn) is a
 * parameter, so shells of one pattern and calibre share their effects. Made once per type and settings, kept.
 */
public final class FireworkEffects {

    /** The shell's velocity: a lift's out of the mortar, a burst's at the burst. */
    public static final EffectParameter.Float VELOCITY_X = EffectParameter.ofFloat("velocityX");
    public static final EffectParameter.Float VELOCITY_Y = EffectParameter.ofFloat("velocityY");
    public static final EffectParameter.Float VELOCITY_Z = EffectParameter.ofFloat("velocityZ");
    /** A lift's time to its burst, seconds: the shell's lifetime. */
    public static final EffectParameter.Float FUSE = EffectParameter.ofFloat("fuse");
    /** The pattern's turn about the vertical (a ring's tilt's azimuth), radians. */
    public static final EffectParameter.Float TURN = EffectParameter.ofFloat("turn");
    /** A ring's tilt from the horizontal, a palm's arms' from the vertical, radians. */
    public static final EffectParameter.Float TILT = EffectParameter.ofFloat("tilt");
    /** The stars' colour: luminance, scene units. */
    public static final EffectParameter.Color COLOR = EffectParameter.ofColor("color");
    /** The second stars' colour (pistil, split, crackle), scene units. */
    public static final EffectParameter.Color SECOND = EffectParameter.ofColor("second");
    /** Charcoal sparks' colour (tails, glitter, the lift's tail), scene units. */
    public static final EffectParameter.Color SPARK = EffectParameter.ofColor("spark");
    /** The flash's colour (the burst's, the mortar's), scene units. */
    public static final EffectParameter.Color FLASH = EffectParameter.ofColor("flash");

    /** The burst's emitters, in order: {@code stars} first. */
    public static final String STARS = "stars";
    /** The lift's emitters: {@code shell} first. */
    public static final String SHELL = "shell";
    /** The longest fuse a lift takes, seconds: its effect's estimate of how long the shell lives. */
    static final float MAX_FUSE = 20;
    /** Fireworks' effects: always simulated, never culled by distance (they are seen from far). */
    static final EffectType TYPE = EffectType.builder("fireworks").build();

    private static final ColorGradient BURN = burnGradient();
    private static final ColorGradient SPARK_FADE = ColorGradient.builder().linear(0, 1.4f, 1.2f, 1, 1)
        .linear(0.3f, 1, 0.8f, 0.6f, 1).linear(1, 0.4f, 0.1f, 0.02f, 0).build();
    private static final ColorGradient FLASH_FADE = ColorGradient.builder().linear(0, 1, 1, 1, 1).linear(1, 0, 0, 0, 0)
        .build();

    private final EngineSettings settings;
    private final Map<Key, EffectData[]> made = new HashMap<>();
    private int stamp;

    private record Key(Pattern pattern, boolean pistil, double calibre) {
    }

    /** Effects made under {@code settings}. */
    public FireworkEffects(EngineSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        stamp = FireworksSettings.designStamp(settings);
    }

    /** The settings they are made under. */
    public EngineSettings settings() {
        return settings;
    }

    /** The star brightness over life, as {@link ShellDesign#burn}: the colour times it, the alpha 1. */
    private static ColorGradient burnGradient() {
        ColorGradient.Builder b = ColorGradient.builder();
        for (int i = 0; i < ShellDesign.BURN_TIMES.length; i++) {
            float v = ShellDesign.BURN_VALUES[i];
            b.linear(ShellDesign.BURN_TIMES[i], v, v, v, 1);
        }
        return b.build();
    }

    /** {@code shell}'s design under the settings now. */
    public ShellDesign design(Shell shell) {
        return ShellDesign.of(shell, settings);
    }

    /** {@code shell}'s lift; made once per pattern, pistil and calibre while the settings stay. */
    public EffectData lift(Shell shell) {
        return effects(shell)[0];
    }

    /** {@code shell}'s burst. */
    public EffectData burst(Shell shell) {
        return effects(shell)[1];
    }

    @AllowAllocation("a shell type's effects, made once")
    private EffectData[] effects(Shell shell) {
        int now = FireworksSettings.designStamp(settings);
        if (now != stamp) {
            made.clear();
            stamp = now;
        }
        Key key = new Key(shell.pattern(), shell.pistil(), shell.calibre());
        EffectData[] e = made.get(key);
        if (e == null) {
            ShellDesign d = design(shell);
            e = new EffectData[] {makeLift(d), makeBurst(d)};
            made.put(key, e);
        }
        return e;
    }

    private float f(com.truesim.engine.settings.Setting<Float> s) {
        return settings.get(s);
    }

    // ------------------------------------------------------------------------------------------ the lift

    @AllowAllocation("made once per shell type")
    private EffectData makeLift(ShellDesign d) {
        float drag = (float) d.shellDrag();
        float tailRate = 80;
        float sparkLife = 0.6f;
        float smoke = f(FireworksSettings.SMOKE);
        Smoke medium = Smoke.of(settings);
        double calibre = d.shell().calibre();
        EffectData.Builder b = EffectData.builder(TYPE)
            .parameter(VELOCITY_X, 0).parameter(VELOCITY_Y, 0).parameter(VELOCITY_Z, 0)
            .parameter(FUSE, MAX_FUSE)
            .parameter(SPARK, 1, 1, 1, 1).parameter(FLASH, 1, 1, 1, 1)
            .emitter(SHELL, e -> {
                e.capacity(1).spawn(Spawn.burst(0, 1))
                    .init(Init.velocity(Bind.param(VELOCITY_X), Bind.param(VELOCITY_Y), Bind.param(VELOCITY_Z)),
                        Init.lifetime(Bind.param(FUSE)), Init.size(Bind.constant(0.3f)),
                        Init.color(Bind.param(SPARK)))
                    .update(Forces.gravity(), Forces.wind(drag, 1, 0))
                    .event(ParticleEvent.emit("tail", Bind.constant(tailRate)))
                    .render(glowing(0.3f));
                if (smoke > 0) {
                    e.event(ParticleEvent.emit("liftSmoke", Bind.constant(smoke)));
                }
            })
            .emitter("tail", e -> e.capacity(capacity(tailRate * sparkLife * 1.4f)).spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.04f), Init.speed(Bind.random(0.5f, 3)), Init.inherit(0.15f, false, false),
                    Init.lifetime(Bind.random(0.25f, sparkLife)), Init.size(Bind.constant(0.18f)),
                    Init.color(Bind.param(SPARK)))
                .update(Forces.gravity(), Forces.wind(2.5f, 1, 0), Over.color(SPARK_FADE))
                .render(glowing(0.18f)))
            .emitter("muzzle", e -> e.capacity(1).spawn(Spawn.burst(0, 1))
                .init(Init.lifetime(Bind.constant(0.09f)), Init.size(Bind.constant(1.6f)),
                    Init.color(Bind.param(FLASH)))
                .update(Over.color(FLASH_FADE))
                .render(Renderer.billboard(Renderer.Facing.POSITION).additive()));
        if (smoke > 0) {
            // the lift charge's smoke out of the mortar (a sixth of a shell's bursting charge's), and the shell's fuse
            // trailing a thin line of it: half a gram a second
            double lift = f(FireworksSettings.BURST_SMOKE) * calibre * calibre / 6;
            float fuse = (float) Ballistics.apexTime(d.launchSpeed(), d.shellDrag());
            b.emitter("liftSmoke", e -> smoke(e, medium, capacity(smoke * fuse * 1.2f), 0.4, 0.5 / smoke)
                    .spawn(Spawn.fromEvents())
                    .init(Shape.sphere(0.2f), Init.speed(Bind.random(0.1f, 0.6f)), Init.inherit(0.05f, false, false)))
                .emitter("muzzleSmoke", e -> smoke(e, medium, 8, 1.0, lift / 8).spawn(Spawn.burst(0, 8))
                    .init(Shape.cone(0.35f, 0.15f), Init.speed(Bind.random(1, 4))));
        }
        return b.build(FireworkModules.MODULES.registry());
    }

    // ------------------------------------------------------------------------------------------ the burst

    @AllowAllocation("made once per shell type")
    private EffectData makeBurst(ShellDesign d) {
        Shell shell = d.shell();
        Pattern p = shell.pattern();
        int stars = d.stars();
        float life = (float) d.lifetime();
        float jitter = (float) ShellDesign.LIFETIME_JITTER;
        float drag = (float) d.drag();
        float glow = (float) d.glow();
        float tailRate = f(FireworksSettings.TAIL_RATE);
        float smokeRate = f(FireworksSettings.SMOKE);
        Smoke medium = Smoke.of(settings);
        float radius = (float) d.radius();
        double calibre = shell.calibre();
        EffectData.Builder b = EffectData.builder(TYPE)
            .parameter(VELOCITY_X, 0).parameter(VELOCITY_Y, 0).parameter(VELOCITY_Z, 0)
            .parameter(TURN, 0).parameter(TILT, 0)
            .parameter(COLOR, 1, 1, 1, 1).parameter(SECOND, 1, 1, 1, 1).parameter(SPARK, 1, 1, 1, 1)
            .parameter(FLASH, 1, 1, 1, 1);
        boolean tails = p.tail() != Pattern.Tail.NONE;
        boolean crossette = p == Pattern.CROSSETTE;
        boolean crackle = p == Pattern.CRACKLE;
        // the stars: by pattern, out of the shell's velocity at the burst
        b.emitter(STARS, e -> {
            e.capacity(stars).spawn(Spawn.burst(0, stars))
                .init(Shape.point(),
                    Init.velocity(Bind.param(VELOCITY_X), Bind.param(VELOCITY_Y), Bind.param(VELOCITY_Z)),
                    pattern(p, stars, (float) d.starSpeed()),
                    Init.lifetime(Bind.random(life * (1 - jitter), life * (1 + jitter))),
                    Init.size(Bind.constant(p == Pattern.PALM ? glow * 1.6f : glow)), Init.color(Bind.param(COLOR)))
                .update(Forces.gravity(), Forces.wind(drag, 1, 0), Over.color(BURN));
            if (p == Pattern.STROBE) {
                e.update(Module.of("fireworks.strobe").with("rate", Bind.random(9, 15)).with("duty", 0.3f)
                    .with("start", 0.12f));
            }
            if (tails) {
                e.event(ParticleEvent.emit("tails", Bind.constant(tailRate(p, tailRate))));
            }
            if (smokeRate > 0) {
                e.event(ParticleEvent.emit("starSmoke", Bind.constant(smokeRate)));
            }
            if (crossette) {
                e.event(ParticleEvent.onDeath("split", Bind.constant(4)));
            }
            if (crackle) {
                e.event(ParticleEvent.onDeath("pellets", Bind.constant(4)));
            }
            e.render(glowing(p == Pattern.PALM ? glow * 1.6f : glow));
        });
        // the bursting charge's flash
        float flashSize = Math.max(2, radius * 0.25f);
        float flashTime = f(FireworksSettings.FLASH_TIME);
        b.emitter("flash", e -> e.capacity(1).spawn(Spawn.burst(0, 1))
            .init(Init.velocity(Bind.param(VELOCITY_X), Bind.param(VELOCITY_Y), Bind.param(VELOCITY_Z)),
                Init.lifetime(Bind.constant(flashTime)), Init.size(Bind.constant(flashSize)),
                Init.color(Bind.param(FLASH)))
            .update(Over.color(FLASH_FADE))
            .render(Renderer.billboard(Renderer.Facing.POSITION).additive()));
        // the bursting charge's smoke, a cloud about the burst's centre: its grams in puffs of a tenth of the radius
        int puffs = smokeRate > 0 ? (int) Math.round(12 + 3 * calibre * calibre) : 0;
        double charge = f(FireworksSettings.BURST_SMOKE) * calibre * calibre;
        if (puffs > 0 && charge > 0) {
            b.emitter("smoke", e -> smoke(e, medium, puffs, radius * 0.1, charge / puffs).spawn(Spawn.burst(0, puffs))
                .init(Shape.sphere(radius * 0.3f), Init.speed(Bind.random(0.5f, 2.5f))));
        }
        if (shell.pistil()) {
            int n = d.pistilStars();
            b.emitter("pistil", e -> e.capacity(n).spawn(Spawn.burst(0, n))
                .init(Shape.point(),
                    Init.velocity(Bind.param(VELOCITY_X), Bind.param(VELOCITY_Y), Bind.param(VELOCITY_Z)),
                    pattern(Pattern.PEONY, n, (float) d.starSpeed() * 0.45f),
                    Init.lifetime(Bind.random(life * (1 - jitter), life * (1 + jitter))),
                    Init.size(Bind.constant(glow)), Init.color(Bind.param(SECOND)))
                .update(Forces.gravity(), Forces.wind(drag, 1, 0), Over.color(BURN))
                .render(glowing(glow)));
        }
        int parents = crossette ? stars * 5 : stars;
        float parentLife = crossette ? (float) (d.lifetime() + ShellDesign.SPLIT_LIFETIME) : life;
        if (tails) {
            boolean glitter = p.tail() == Pattern.Tail.GLITTER;
            float sparkLife = glitter ? 2.6f : p.tail() == Pattern.Tail.COMET ? 1.1f : 0.7f;
            float rate = tailRate(p, tailRate);
            float sparkSize = glow * (p.tail() == Pattern.Tail.COMET ? 0.6f : 0.4f);
            b.emitter("tails", e -> e.capacity(capacity(parents * rate * Math.min(sparkLife, parentLife) * 1.15f))
                .spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.03f), Init.speed(Bind.random(0.2f, glitter ? 0.8f : 2.5f)),
                    Init.inherit(glitter ? 0.25f : 0.35f, false, false),
                    Init.lifetime(Bind.random(sparkLife * 0.45f, sparkLife)),
                    Init.size(Bind.constant(sparkSize)), Init.color(Bind.param(SPARK)))
                .update(Forces.gravity(Bind.constant(glitter ? 0.35f : 1)), Forces.wind(glitter ? 2.4f : 2, 1, 0),
                    Over.color(SPARK_FADE))
                .render(glowing(sparkSize)));
        }
        if (crossette) {
            float splitLife = (float) ShellDesign.SPLIT_LIFETIME;
            b.emitter("split", e -> {
                e.capacity(stars * 4).spawn(Spawn.fromEvents())
                    .init(Init.inherit(1, false, false),
                        Module.of("fireworks.pattern").with("kind", Pattern.Layout.CROSS.ordinal()).with("arms", 4)
                            .with("speed", (float) d.childSpeed()).with("jitter", 0.12f)
                            .with("speedJitter", 0.06f),
                        Init.lifetime(Bind.random(splitLife * (1 - jitter), splitLife * (1 + jitter))),
                        Init.size(Bind.constant(glow)), Init.color(Bind.param(SECOND)))
                    .update(Forces.gravity(), Forces.wind(drag, 1, 0), Over.color(BURN))
                    .event(ParticleEvent.emit("tails", Bind.constant(tailRate(p, tailRate))))
                    .render(glowing(glow));
                if (smokeRate > 0) {
                    e.event(ParticleEvent.emit("starSmoke", Bind.constant(smokeRate)));
                }
            });
        }
        if (crackle) {
            // each star bursts into pellets that pop a moment later, each a flash of a micro-star: the crackle
            b.emitter("pellets", e -> e.capacity(stars * 4).spawn(Spawn.fromEvents())
                .init(Shape.sphere(0.05f), Init.speed(Bind.random(2, 7)), Init.inherit(0.6f, false, false),
                    Init.lifetime(Bind.random(0.05f, 0.7f)))
                .update(Forces.gravity(), Forces.wind(2, 1, 0))
                .event(ParticleEvent.onDeath("pops", Bind.constant(1))));
            b.emitter("pops", e -> e.capacity(stars * 4).spawn(Spawn.fromEvents())
                .init(Init.inherit(0.2f, false, false), Init.lifetime(Bind.random(0.04f, 0.09f)),
                    Init.size(Bind.constant(glow * 0.9f)), Init.color(Bind.param(SECOND)))
                .update(Over.color(FLASH_FADE))
                .render(glowing(glow * 0.9f)));
        }
        if (smokeRate > 0) {
            // what the stars leave as they burn: their grams a second in puffs along their paths
            float puffs2 = parents * smokeRate * parentLife * 1.2f;
            double grams = f(FireworksSettings.SMOKE_MASS) / smokeRate;
            // scattered a few deviations about the path: puffs every star leaves at once would otherwise lie on
            // spheres about the burst, showing as rings
            float puff = f(FireworksSettings.SMOKE_PUFF);
            b.emitter("starSmoke", e -> smoke(e, medium, capacity(puffs2), puff, grams)
                .spawn(Spawn.fromEvents())
                .init(Shape.sphere(2.5f * puff), Init.speed(Bind.random(0.05f, 0.6f)), Init.inherit(0.2f, false, false)));
        }
        return b.build(FireworkModules.MODULES.registry());
    }

    /** Sparks a second a star's tail throws: a palm's comets the most, a willow's glitter more than a chrysanthemum's. */
    private static float tailRate(Pattern p, float rate) {
        return switch (p.tail()) {
            case COMET -> rate * 3;
            case GLITTER -> rate * 1.2f;
            default -> rate;
        };
    }

    /** The pattern module of {@code p} for {@code stars} stars at {@code speed} m/s, turned by the parameters. */
    @AllowAllocation("made once per shell type")
    static Module pattern(Pattern p, int stars, float speed) {
        Module m = Module.of("fireworks.pattern").with("kind", p.layout().ordinal()).with("count", stars)
            .with("speed", speed).with("jitter", (float) p.jitter()).with("speedJitter", 0.04f)
            .with("turn", Bind.param(TURN)).with("tilt", Bind.param(TILT));
        return p == Pattern.PALM ? m.with("speedJitter", 0.02f) : m;
    }

    /**
     * Smoke puffs of the medium {@code smoke}: Gaussians of {@code grams} and deviation {@code sigma0} metres at first,
     * spreading by turbulence (their size over life) so their optical depth falls as their mass stays (their alpha,
     * the depth at the centre, over life), their colour the albedo; drawn as a medium (three deviations either way,
     * at half resolution), scattering by the phase function the lights about them (bursts light the smoke of those
     * before), shadowing themselves; drifting with the wind and rising a little. Every puff lives the smoke's time, so
     * the curves over its life are its spreading's.
     */
    @AllowAllocation("made once per shell type")
    static EmitterData.Builder smoke(EmitterData.Builder e, Smoke smoke, int capacity, double sigma0,
        double grams) {
        float tau = (float) smoke.centralDepth(grams, sigma0);
        float albedo = (float) smoke.albedo();
        return e.capacity(Math.max(1, capacity))
            .init(Init.lifetime(Bind.constant((float) smoke.time())), Init.size(Bind.constant((float) (6 * sigma0))),
                Init.color(Bind.color(albedo, albedo, albedo, tau)))
            .update(Forces.gravity(Bind.constant(-0.004f)), Forces.wind(0.6f, 1, 0.3f),
                Over.size(smoke.sizeOverLife(sigma0)), Over.color(smoke.depthOverLife(sigma0)))
            .render(Renderer.billboard(Renderer.Facing.POSITION).lighting(Renderer.Lighting.PER_VERTEX).alphaToAdd(0)
                .set("medium", 1).set("phase", (float) smoke.phase()).set("half", 1)
                .set("soft", (float) Math.max(1, 3 * sigma0)));
    }

    /**
     * Glowing sprites of {@code size} metres: additive, streaked by their speed over the shutter's time (their light
     * kept over the streak) and at least the least pixels across (likewise), so their light is what their colour
     * says however far and fast they are.
     */
    private Renderer glowing(float size) {
        float shutter = f(FireworksSettings.SHUTTER);
        Renderer r = Renderer.billboard(shutter > 0 ? Renderer.Facing.VELOCITY : Renderer.Facing.POSITION).additive()
            .set("minPixels", f(FireworksSettings.MIN_PIXELS));
        return shutter > 0 ? r.set("stretch", shutter / size).set("conserve", 1) : r;
    }

    private static int capacity(float particles) {
        return Math.max(64, (int) Math.ceil(particles));
    }
}
