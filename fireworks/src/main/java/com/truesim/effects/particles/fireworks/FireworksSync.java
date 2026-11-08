package com.truesim.effects.particles.fireworks;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import com.truesim.common.math.TransformArray;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectParameter;
import com.truesim.scene.effects.Effects;
import com.truesim.scene.effects.Hash;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.Objects;

/**
 * Plays the scene's fireworks shows ({@link FireworkDisplay} components) through its effects, lights and sounds: each
 * frame it fires the shells whose time has come (their lift effects, out of their mortars at the speed that bursts them
 * at their height), bursts each at its apex (where and when {@link Ballistics} puts it, the wind the world's at its
 * launch: the particles' own motion), its burst effect turned by its seed, lights each burst by a point light
 * ({@link LightSource} on a node of its own, so the engine's light sync places it) at the centre of its stars, as bright
 * as its stars burn ({@link ShellDesign#light}) and reaching as far as it lights more than the threshold, and adds the
 * sounds of launches, bursts and crackles to {@link #sounds()} for audio.
 *
 * <p>Every shell's randomness comes from its show's seed and its cue: a show plays the same every time. Its clock is
 * the effects' (scaled and paused with them). Call {@link #update} each frame before the effects sync and the scene's
 * update (its lights move with it). Allocates nothing per frame once its shells' and lights' pools have grown, but for
 * the effect handles it plays.
 */
public final class FireworksSync {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    /** Sounds kept for audio. */
    public static final int SOUND_CAPACITY = 4096;
    /** The most lights a burst is spread over. */
    static final int MAX_LIGHTS_PER_BURST = 8;
    /**
     * Where a burst's lights stand, as directions from its centre, for 1, 2, 4, 6 and 8 lights: the centre, the poles,
     * a tetrahedron's, an octahedron's and a cube's corners.
     */
    private static final double[][][] SPREAD = {
        {{0, 0, 0}},
        {{0, 1, 0}, {0, -1, 0}},
        {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}},
        {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}},
        {{1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {1, -1, -1}, {-1, 1, 1}, {-1, 1, -1}, {-1, -1, 1}, {-1, -1, -1}}};
    /** Crackle sounds per crackling shell: volleys spread over its micro-stars' popping. */
    static final int CRACKLE_VOLLEYS = 8;

    private final Effects effects;
    private final SceneGraph scene;
    private final ComponentStore<FireworkDisplay> displays;
    private final ComponentStore<LightSource> lightStore;
    private final EngineSettings settings;
    private final FireworkEffects made;
    private final FireworkSounds sounds = new FireworkSounds(SOUND_CAPACITY);
    private final float[] rgb = new float[3];
    private double now;

    // by node slot: the display there, its node, when its show started, its next cue
    private long[] nodes = new long[0];
    private FireworkDisplay[] playing = new FireworkDisplay[0];
    private double[] starts = new double[0];
    private int[] nextCue = new int[0];

    private Flight[] flights = new Flight[0];
    private int flightCount;
    private Flight[] spare = new Flight[0];
    private int spareCount;
    private long launched;
    private long burst;
    private long lastLift = -1;
    private long lastBurst = -1;

    /** A shell in the air or bursting, and its light: pooled. */
    private static final class Flight {
        Shell shell;
        ShellDesign design;
        int seed;
        double launch;
        double burstTime;
        final double[] burstAt = new double[3];
        final double[] burstVelocity = new double[3];
        final double[] wind = new double[3];
        boolean burst;
        int crackles;
        double end;
        /** Its lights' nodes and lights (made once per pooled shell), how many it shows, whether they shine. */
        final long[] nodes = new long[MAX_LIGHTS_PER_BURST];
        final LightSource[] lights = new LightSource[MAX_LIGHTS_PER_BURST];
        int count;
        boolean lit;
        double intensity;
        float turn;
    }

    /** Plays the shows of {@code effects}' scene through its effects, with the fireworks' {@code settings}. */
    @AllowAllocation("made once")
    public FireworksSync(Effects effects, EngineSettings settings) {
        this.effects = Objects.requireNonNull(effects, "effects");
        this.settings = Objects.requireNonNull(settings, "settings");
        scene = effects.scene();
        displays = scene.components().store(FireworkDisplay.TYPE);
        lightStore = scene.components().store(LightSource.TYPE);
        made = new FireworkEffects(settings);
    }

    /** The shells' effects, made once per type. */
    public FireworkEffects shells() {
        return made;
    }

    /** The sounds of launches, bursts and crackles, on {@link #time()}'s clock. */
    public FireworkSounds sounds() {
        return sounds;
    }

    /** Its clock: seconds the effects moved on since it was made. */
    public double time() {
        return now;
    }

    /** Shells fired so far. */
    public long launched() {
        return launched;
    }

    /** Shells burst so far. */
    public long bursts() {
        return burst;
    }

    /** The effect id of the last shell's lift fired, -1 before any. */
    public long lastLift() {
        return lastLift;
    }

    /** The effect id of the last burst, -1 before any. */
    public long lastBurst() {
        return lastBurst;
    }

    /** Shells in the air or bursting now (their lights and sounds still to come). */
    public int flights() {
        return flightCount;
    }

    /** The burst lights shining now: their light components in the scene. */
    public int lit() {
        int n = 0;
        for (int i = 0; i < flightCount; i++) {
            n += flights[i].lit ? flights[i].count : 0;
        }
        return n;
    }

    /** Moves the shows on by {@code seconds} of the world's time (the effects' scale and pause applied). */
    public void update(double seconds) {
        double before = now;
        now += effects.paused() ? 0 : seconds * effects.timeScale();
        // displays gone or replaced stop firing
        for (int slot = 0; slot < nodes.length; slot++) {
            if (playing[slot] != null && (!scene.contains(nodes[slot]) || displays.get(nodes[slot]) != playing[slot])) {
                playing[slot] = null;
                nodes[slot] = NodeHandle.NONE;
            }
        }
        TransformArray world = scene.worldTransforms();
        for (int i = 0; i < displays.size(); i++) {
            long node = displays.node(i);
            FireworkDisplay display = displays.value(i);
            int slot = NodeHandle.index(node);
            if (slot >= nodes.length) {
                grow(slot + 1);
            }
            if (playing[slot] != display || nodes[slot] != node) {
                playing[slot] = display;
                nodes[slot] = node;
                starts[slot] = before + display.delay(); // added between the frames: from this frame's start
                nextCue[slot] = 0;
            }
            FireworkShow show = display.show();
            while (nextCue[slot] < show.size() && starts[slot] + show.cue(nextCue[slot]).time() <= now) {
                launch(show, nextCue[slot], starts[slot], world, slot);
                nextCue[slot]++;
            }
        }
        float scale = settings.get(FireworksSettings.LUMINANCE_SCALE);
        float threshold = settings.get(FireworksSettings.LIGHT_THRESHOLD);
        int kept = 0;
        for (int i = 0; i < flightCount; i++) {
            Flight f = flights[i];
            if (!f.burst && now >= f.burstTime) {
                burst(f, scale);
            }
            if (f.burst) {
                crackles(f);
                light(f, scale, threshold);
            }
            if (f.burst && now > f.end) {
                retire(f);
            } else {
                flights[kept++] = f;
            }
        }
        for (int i = kept; i < flightCount; i++) {
            flights[i] = null;
        }
        flightCount = kept;
        limitLights();
    }

    // ------------------------------------------------------------------------------------------ shells

    @AllowAllocation("an effect handle per shell")
    private void launch(FireworkShow show, int cueIndex, double start, TransformArray world, int slot) {
        FireworkShow.Cue cue = show.cue(cueIndex);
        Flight f = flight();
        f.shell = cue.shell();
        f.design = made.design(cue.shell());
        f.seed = Hash.combine(show.seed(), cueIndex);
        f.launch = start + cue.time();
        f.burst = false;
        f.crackles = 0;
        // the mortar: the node's frame, the cue's offset and lean
        MemorySegment s = world.segment();
        long o = slot * world.stride();
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        double[] site = turn(qx, qy, qz, qw, cue.x(), 0, cue.z());
        site[0] += s.get(DOUBLE, o);
        site[1] += s.get(DOUBLE, o + 8);
        site[2] += s.get(DOUBLE, o + 16);
        double st = Maths.sin(cue.tilt());
        double[] up = turn(qx, qy, qz, qw, st * Maths.cos(cue.azimuth()), Maths.cos(cue.tilt()),
            st * Maths.sin(cue.azimuth()));
        double speed = f.design.launchSpeed();
        double k = f.design.shellDrag();
        for (int a = 0; a < 3; a++) {
            f.wind[a] = effects.wind(a);
        }
        double[] v0 = {up[0] * speed, up[1] * speed, up[2] * speed};
        double fuse = Ballistics.apexTime(v0[1], k, f.wind[1]);
        f.burstTime = f.launch + fuse;
        for (int a = 0; a < 3; a++) {
            f.burstAt[a] = Ballistics.position(a, site[a], v0[a], k, f.wind[a], fuse);
            f.burstVelocity[a] = Ballistics.velocity(a, v0[a], k, f.wind[a], fuse);
        }
        f.end = f.burstTime + Math.max(f.design.lightEnd(), crackleEnd(f));
        float scale = settings.get(FireworksSettings.LUMINANCE_SCALE);
        Effect lift = effects.play(made.lift(f.shell), site[0], site[1], site[2])
            .seed(Hash.combine(f.seed, 1))
            .set(FireworkEffects.VELOCITY_X, (float) v0[0]).set(FireworkEffects.VELOCITY_Y, (float) v0[1])
            .set(FireworkEffects.VELOCITY_Z, (float) v0[2]).set(FireworkEffects.FUSE, (float) fuse);
        setColor(lift, FireworkEffects.SPARK, Colorant.CHARCOAL_GOLD, settings.get(FireworksSettings.SPARK_INTENSITY),
            0.18, scale);
        setColor(lift, FireworkEffects.FLASH, ShellDesign.FLASH_RGB, f.design.flashCandela() * 0.04, 1.6, scale);
        lastLift = lift.id();
        float inches = (float) f.shell.calibre();
        sounds.add(f.launch, FireworkSounds.Kind.LAUNCH, site[0], site[1], site[2],
            (float) (122 + 20 * Maths.log10(inches / 3)), f.seed, inches);
        launched++;
    }

    @AllowAllocation("an effect handle per shell")
    private void burst(Flight f, float scale) {
        f.burst = true;
        ShellDesign d = f.design;
        Shell shell = f.shell;
        Pattern p = shell.pattern();
        float turn = (float) (2 * Math.PI * Hash.random(f.seed, 2));
        f.turn = turn;
        float tilt = switch (p) {
            case RING -> (float) (1.2 * Hash.random(f.seed, 3));
            case PALM -> (float) (0.55 + 0.25 * Hash.random(f.seed, 3));
            default -> 0;
        };
        Effect e = effects.play(made.burst(shell), f.burstAt[0], f.burstAt[1], f.burstAt[2])
            .seed(Hash.combine(f.seed, 2))
            .set(FireworkEffects.VELOCITY_X, (float) f.burstVelocity[0])
            .set(FireworkEffects.VELOCITY_Y, (float) f.burstVelocity[1])
            .set(FireworkEffects.VELOCITY_Z, (float) f.burstVelocity[2])
            .set(FireworkEffects.TURN, turn).set(FireworkEffects.TILT, tilt);
        double glow = d.glow();
        setColor(e, FireworkEffects.COLOR, shell.color(), d.starCandela(), p == Pattern.PALM ? glow * 1.6 : glow,
            scale);
        if (p == Pattern.CRACKLE) {
            Colorant pop = shell.second() != null ? shell.second() : Colorant.TITANIUM_SILVER;
            setColor(e, FireworkEffects.SECOND, pop, settings.get(FireworksSettings.CRACKLE_INTENSITY), glow * 0.9,
                scale);
        } else {
            setColor(e, FireworkEffects.SECOND, shell.secondOrColor(), d.secondCandela(), glow, scale);
        }
        setColor(e, FireworkEffects.SPARK, Colorant.CHARCOAL_GOLD, settings.get(FireworksSettings.SPARK_INTENSITY),
            glow * (p.tail() == Pattern.Tail.COMET ? 0.6 : 0.4), scale);
        setColor(e, FireworkEffects.FLASH, ShellDesign.FLASH_RGB, d.flashCandela(), Math.max(2, d.radius() * 0.25),
            scale);
        lastBurst = e.id();
        float inches = (float) shell.calibre();
        sounds.add(f.burstTime, FireworkSounds.Kind.BURST, f.burstAt[0], f.burstAt[1], f.burstAt[2],
            (float) (140 + 20 * Maths.log10(inches / 3)), f.seed, inches);
        burst++;
    }

    /** A colour parameter: {@code c}'s colour at the luminance of a sprite of {@code size} giving {@code candela}. */
    private static void setColor(Effect e, EffectParameter.Color parameter, Colorant c, double candela, double size,
        float scale) {
        float l = (float) (ShellDesign.spriteLuminance(candela, size) * scale);
        e.set(parameter, c.rgb(0) * l, c.rgb(1) * l, c.rgb(2) * l, 1);
    }

    private static void setColor(Effect e, EffectParameter.Color parameter, float[] rgb, double candela, double size,
        float scale) {
        float l = (float) (ShellDesign.spriteLuminance(candela, size) * scale);
        e.set(parameter, rgb[0] * l, rgb[1] * l, rgb[2] * l, 1);
    }

    /** When a crackling shell's last volley is heard made, seconds after its burst; 0 for others. */
    private static double crackleEnd(Flight f) {
        return f.shell.pattern() == Pattern.CRACKLE ? crackleTime(f, CRACKLE_VOLLEYS - 1) - f.burstTime : 0;
    }

    /** When crackle volley {@code k} is made: spread over the stars' deaths and their pellets' fuses. */
    static double crackleTime(Flight f, int k) {
        double first = f.design.lifetime() * (1 - ShellDesign.LIFETIME_JITTER) + 0.05;
        double span = f.design.lifetime() * 2 * ShellDesign.LIFETIME_JITTER + 0.65;
        return f.burstTime + first + span * (k + 0.5) / CRACKLE_VOLLEYS;
    }

    private void crackles(Flight f) {
        if (f.shell.pattern() != Pattern.CRACKLE) {
            return;
        }
        while (f.crackles < CRACKLE_VOLLEYS && crackleTime(f, f.crackles) <= now) {
            double t = crackleTime(f, f.crackles);
            double age = t - f.burstTime;
            double k = f.design.drag();
            sounds.add(t, FireworkSounds.Kind.CRACKLE, centre(f, 0, age, k), centre(f, 1, age, k),
                centre(f, 2, age, k), 118, f.seed, (float) f.shell.calibre());
            f.crackles++;
        }
    }

    /** The burst's centre {@code age} seconds after it: its stars' mean, moving as the shell would. */
    private static double centre(Flight f, int axis, double age, double k) {
        return Ballistics.position(axis, f.burstAt[axis], f.burstVelocity[axis], k, f.wind[axis], age);
    }

    // ------------------------------------------------------------------------------------------ lights

    /**
     * The burst's light, spread over {@link FireworksSettings#LIGHTS_PER_BURST} point lights about its centre at its
     * stars' distance from it (the corners of a tetrahedron, ...; all at the centre while the stars are there), each a
     * share of the stars' intensity: from afar as one light at the centre, and near it (its smoke, the stars'
     * neighbourhood) lit as the stars spread around light it rather than as a point at the centre would.
     */
    @AllowAllocation("a burst's light nodes per pooled shell, made once")
    private void light(Flight f, float scale, float threshold) {
        double age = now - f.burstTime;
        double candela = f.design.light(age, rgb);
        f.intensity = candela;
        if (!(candela > 0)) {
            unlight(f);
            return;
        }
        if (!f.lit) {
            f.count = spread(settings.get(FireworksSettings.LIGHTS_PER_BURST));
        }
        double[][] spread = SPREAD[spreadIndex(f.count)];
        double k = f.design.drag();
        double radius = Math.min(Ballistics.position(0, 0, f.design.starSpeed(), k, 0, age), f.design.radius());
        double share = candela / f.count;
        double c = Maths.cos(f.turn);
        double sn = Maths.sin(f.turn);
        for (int i = 0; i < f.count; i++) {
            if (f.nodes[i] == NodeHandle.NONE || !scene.contains(f.nodes[i])) {
                f.nodes[i] = scene.create();
                f.lights[i] = LightSource.point(1, 1, 1, 0);
            }
            double[] d = spread[i];
            double norm = f.count == 1 ? 0 : radius / Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            scene.setTranslation(f.nodes[i], centre(f, 0, age, k) + norm * (c * d[0] - sn * d[2]),
                centre(f, 1, age, k) + norm * d[1], centre(f, 2, age, k) + norm * (sn * d[0] + c * d[2]));
            f.lights[i].color(rgb[0], rgb[1], rgb[2]).intensity(share * scale).range(Math.sqrt(share / threshold));
            if (!f.lit) {
                lightStore.set(f.nodes[i], f.lights[i]);
            }
        }
        f.lit = true;
    }

    /** The lights a burst is spread over, of those asked for: 1, 2, 4, 6 or 8. */
    static int spread(int asked) {
        return asked >= 8 ? 8 : asked >= 6 ? 6 : asked >= 4 ? 4 : asked >= 2 ? 2 : 1;
    }

    private static int spreadIndex(int count) {
        return switch (count) {
            case 1 -> 0;
            case 2 -> 1;
            case 4 -> 2;
            case 6 -> 3;
            default -> 4;
        };
    }

    private void unlight(Flight f) {
        if (f.lit) {
            for (int i = 0; i < f.count; i++) {
                lightStore.remove(f.nodes[i]);
            }
            f.lit = false;
        }
        f.intensity = 0;
    }

    /** At most the setting's lights: the dimmest bursts' go out this frame. */
    private void limitLights() {
        int most = settings.get(FireworksSettings.MAX_LIGHTS);
        int lit = lit();
        while (lit > most) {
            Flight dimmest = null;
            for (int i = 0; i < flightCount; i++) {
                Flight f = flights[i];
                if (f.lit && (dimmest == null || f.intensity < dimmest.intensity)) {
                    dimmest = f;
                }
            }
            lit -= dimmest.count;
            unlight(dimmest);
        }
    }

    // ------------------------------------------------------------------------------------------ pools

    @AllowAllocation("growing the pool of shells")
    private Flight flight() {
        Flight f = spareCount > 0 ? spare[--spareCount] : new Flight();
        if (flightCount == flights.length) {
            flights = Arrays.copyOf(flights, Math.max(16, flightCount * 2));
        }
        flights[flightCount++] = f;
        return f;
    }

    @AllowAllocation("growing the pool of shells")
    private void retire(Flight f) {
        unlight(f);
        f.shell = null;
        f.design = null;
        if (spareCount == spare.length) {
            spare = Arrays.copyOf(spare, Math.max(16, spareCount * 2));
        }
        spare[spareCount++] = f;
    }

    @AllowAllocation("growing with the scene")
    private void grow(int length) {
        int n = Math.max(length, nodes.length * 2);
        nodes = Arrays.copyOf(nodes, n);
        playing = Arrays.copyOf(playing, n);
        starts = Arrays.copyOf(starts, n);
        nextCue = Arrays.copyOf(nextCue, n);
    }

    /** (x, y, z) turned by the quaternion (qx, qy, qz, qw). */
    private static double[] turn(double qx, double qy, double qz, double qw, double x, double y, double z) {
        // v + 2 w (q x v) + 2 q x (q x v)
        double tx = 2 * (qy * z - qz * y);
        double ty = 2 * (qz * x - qx * z);
        double tz = 2 * (qx * y - qy * x);
        return new double[] {x + qw * tx + (qy * tz - qz * ty), y + qw * ty + (qz * tx - qx * tz),
            z + qw * tz + (qx * ty - qy * tx)};
    }

    /** Removes the lights' nodes from the scene. */
    public void close() {
        for (int i = 0; i < flightCount; i++) {
            retire(flights[i]);
        }
        flightCount = 0;
        for (int i = 0; i < spareCount; i++) {
            for (int k = 0; k < MAX_LIGHTS_PER_BURST; k++) {
                long node = spare[i].nodes[k];
                if (node != NodeHandle.NONE && scene.contains(node)) {
                    scene.remove(node);
                }
                spare[i].nodes[k] = NodeHandle.NONE;
            }
        }
    }
}
