package com.truesim.effects.particles.fireworks;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import com.truesim.engine.settings.SettingRegistry;

/**
 * The fireworks' tunables, settings of the engine's registry (listed with every other setting): the physics of
 * shells and stars, how bright they burn, how much smoke they leave, their lights' reach and number, and sound's speed.
 * Read when a shell's effects are made (a change makes them anew for the next shells) or each frame (lights, sounds).
 */
public final class FireworksSettings {

    /** Scales every colorant's luminous intensity (Colorant.candela): 1 is the colorants' own. */
    public static final Setting<Float> STAR_INTENSITY = Setting.decimal("fireworks.starIntensity", 1, 0, 100,
        "how bright stars burn, times the colorants' candela");
    /** A charcoal spark's luminous intensity in tails, glitter and the lift's tail, candela. */
    public static final Setting<Float> SPARK_INTENSITY = Setting.decimal("fireworks.sparkIntensity", 10, 0, 1000,
        "a tail spark's luminous intensity, candela");
    /** A crackle's micro-star's luminous intensity while it flashes, candela. */
    public static final Setting<Float> CRACKLE_INTENSITY = Setting.decimal("fireworks.crackleIntensity", 600, 0, 1e5f,
        "a crackling micro-star's luminous intensity, candela");
    /** The bursting charge's flash, candela per square inch of calibre, fading over {@link #FLASH_TIME}. */
    public static final Setting<Float> FLASH_INTENSITY = Setting.decimal("fireworks.flashIntensity", 25_000, 0, 1e8f,
        "the burst's flash, candela per square inch of calibre");
    /** How long the bursting charge's flash lasts, seconds. */
    public static final Setting<Float> FLASH_TIME = Setting.decimal("fireworks.flashTime", 0.12f, 0.01f, 2,
        "how long the burst's flash lasts, seconds");
    /**
     * How large a star is drawn, metres: its glow, not the star (a centimetre or two). Its luminance is its intensity
     * over the sprite's area, so the light reaching the camera does not depend on it.
     */
    public static final Setting<Float> GLOW = Setting.decimal("fireworks.glow", 0.55f, 0.01f, 10,
        "how large a star's glow is drawn, metres");
    /**
     * Motion blur: stars and sparks are drawn as streaks of their speed times this, seconds (a camera's shutter, an
     * eye's persistence), their light kept over the streak; 0 for round sprites.
     */
    public static final Setting<Float> SHUTTER = Setting.decimal("fireworks.shutter", 1 / 30f, 0, 1,
        "how long stars streak by their speed, seconds (motion blur)");
    /** Stars and sparks are drawn at least this many pixels across, their light kept: no flicker below a pixel. */
    public static final Setting<Float> MIN_PIXELS = Setting.decimal("fireworks.minPixels", 1.5f, 0, 16,
        "the least size stars and sparks are drawn, pixels");
    /** Burst radius per inch of calibre, metres: some 10 (a burst of 70 ft across per inch). */
    public static final Setting<Float> BURST_RADIUS = Setting.decimal("fireworks.burstRadius", 10, 0.5f, 100,
        "burst radius per inch of calibre, metres");
    /** Burst height per inch of calibre, metres: some 33 (100 ft per inch). */
    public static final Setting<Float> BURST_HEIGHT = Setting.decimal("fireworks.burstHeight", 33, 1, 300,
        "burst height per inch of calibre, metres");
    /** The air's drag on a rising shell, per second. */
    public static final Setting<Float> SHELL_DRAG = Setting.decimal("fireworks.shellDrag", 0.12f, 0, 10,
        "the air's drag on a rising shell, per second");
    /** Charcoal sparks a star with a tail throws a second. */
    public static final Setting<Float> TAIL_RATE = Setting.decimal("fireworks.tailRate", 28, 0, 1000,
        "sparks a second of a star's tail");
    /** Smoke a burning star leaves as puffs: puffs a second (0: no smoke at all). */
    public static final Setting<Float> SMOKE = Setting.decimal("fireworks.smoke", 8, 0, 100,
        "puffs of smoke a second a burning star leaves (0: none)");
    /** Smoke a burning star leaves, grams a second. */
    public static final Setting<Float> SMOKE_MASS = Setting.decimal("fireworks.smokeMass", 1, 0, 100,
        "grams of smoke a second a burning star leaves");
    /** Smoke a shell's bursting charge leaves, grams per square inch of calibre. */
    public static final Setting<Float> BURST_SMOKE = Setting.decimal("fireworks.burstSmoke", 1.8f, 0, 1000,
        "grams of smoke a shell's bursting charge leaves per square inch of calibre");
    /** A star's smoke puff's deviation at first, metres (its sprite is three either way). */
    public static final Setting<Float> SMOKE_PUFF = Setting.decimal("fireworks.smokePuff", 0.8f, 0.05f, 20,
        "the deviation of a star's smoke puff at first, metres");
    /** The smoke's mass extinction coefficient, m²/g. */
    public static final Setting<Float> SMOKE_EXTINCTION = Setting.decimal("fireworks.smokeExtinction", 3, 0, 100,
        "the smoke's mass extinction coefficient, square metres per gram");
    /** The smoke's single-scattering albedo. */
    public static final Setting<Float> SMOKE_ALBEDO = Setting.decimal("fireworks.smokeAlbedo", 0.9f, 0, 1,
        "the smoke's single-scattering albedo");
    /** The smoke's phase function's asymmetry (Henyey-Greenstein): 0 evenly, towards 1 forwards. */
    public static final Setting<Float> SMOKE_PHASE = Setting.decimal("fireworks.smokePhase", 0.6f, -0.95f, 0.95f,
        "the asymmetry of the smoke's scattering (Henyey-Greenstein g)");
    /** How fast smoke puffs spread by turbulence, m²/s. */
    public static final Setting<Float> SMOKE_DIFFUSIVITY = Setting.decimal("fireworks.smokeDiffusivity", 0.5f, 0, 100,
        "the turbulent diffusivity smoke puffs spread by, square metres a second");
    /** How long smoke lingers, seconds. */
    public static final Setting<Float> SMOKE_TIME = Setting.decimal("fireworks.smokeTime", 30, 1, 600,
        "how long fireworks' smoke lingers, seconds");
    /**
     * The illuminance a burst's light reaches down to, lux: its range is where it falls to this, so the lights' clusters
     * hold it only where it matters.
     */
    public static final Setting<Float> LIGHT_THRESHOLD = Setting.decimal("fireworks.lightThreshold", 0.002f, 1e-6f, 10,
        "the illuminance a burst's light reaches down to, lux");
    /** The most burst lights at once: past it, the dimmest bursts' go. */
    public static final Setting<Integer> MAX_LIGHTS = Setting.integer("fireworks.maxLights", 96, 0, 4096,
        "the most fireworks' burst lights at once");
    /**
     * The point lights a burst's light is spread over (1, 2, 4, 6 or 8), about its centre at its stars' distance: a
     * single one lights what is near the centre as if all the stars were there.
     */
    public static final Setting<Integer> LIGHTS_PER_BURST = Setting.integer("fireworks.lightsPerBurst", 4, 1, 8,
        "the point lights a burst's light is spread over");
    /** The speed of sound, m/s: sounds reach a listener this late per metre. */
    public static final Setting<Float> SPEED_OF_SOUND = Setting.decimal("fireworks.speedOfSound", 343, 1, 2000,
        "the speed of sound, m/s");
    /**
     * Scene colour per cd/m² (and light intensities per candela): a renderer pre-exposing its scene colour (1.2 x
     * 2^-EV100) sets it; 1 for physical units as they are.
     */
    public static final Setting<Float> LUMINANCE_SCALE = Setting.decimal("fireworks.luminanceScale", 1, 1e-9f, 1e9f,
        "scene colour per cd/m², for pre-exposed scene colour");

    private static final Setting<?>[] ALL = {STAR_INTENSITY, SPARK_INTENSITY, CRACKLE_INTENSITY, FLASH_INTENSITY,
        FLASH_TIME, GLOW, SHUTTER, MIN_PIXELS, BURST_RADIUS, BURST_HEIGHT, SHELL_DRAG, TAIL_RATE, SMOKE, SMOKE_MASS, BURST_SMOKE,
        SMOKE_PUFF, SMOKE_EXTINCTION, SMOKE_ALBEDO, SMOKE_PHASE, SMOKE_DIFFUSIVITY, SMOKE_TIME, LIGHT_THRESHOLD, MAX_LIGHTS, LIGHTS_PER_BURST, SPEED_OF_SOUND, LUMINANCE_SCALE};

    /** The settings that change shells' effects: a change of one makes them anew. */
    private static final Setting<?>[] DESIGN = {STAR_INTENSITY, SPARK_INTENSITY, CRACKLE_INTENSITY, FLASH_INTENSITY,
        FLASH_TIME, GLOW, SHUTTER, MIN_PIXELS, BURST_RADIUS, BURST_HEIGHT, SHELL_DRAG, TAIL_RATE, SMOKE, SMOKE_MASS, BURST_SMOKE,
        SMOKE_PUFF, SMOKE_EXTINCTION, SMOKE_ALBEDO, SMOKE_PHASE, SMOKE_DIFFUSIVITY, SMOKE_TIME};

    private FireworksSettings() {
    }

    /** Lists the fireworks' settings in {@code registry} (hidden from settings screens, as the effects' are). */
    public static void register(SettingRegistry registry) {
        registry.addHidden(ALL);
    }

    /** A number that changes when a setting shells' effects are made from changes. */
    @com.truesim.annotations.AllowAllocation("hashing settings' values, when shells are designed")
    static int designStamp(EngineSettings settings) {
        int h = 17;
        for (Setting<?> s : DESIGN) {
            h = 31 * h + settings.get(s).hashCode();
        }
        return h;
    }
}
