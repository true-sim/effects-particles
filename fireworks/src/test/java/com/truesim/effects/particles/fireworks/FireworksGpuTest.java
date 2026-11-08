package com.truesim.effects.particles.fireworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.particles.layout.ParticleLayout;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.effects.Bind;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Forces;
import com.truesim.scene.effects.Init;
import com.truesim.scene.effects.Module;
import com.truesim.scene.effects.Spawn;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The fireworks on the GPU, validated: the pattern module laying stars out as its Java mirror does, each star once;
 * shells and stars flying as the closed form has them through gravity, drag and wind; shells bursting at their apex
 * through the scene's display; the same particles from the same seed; and a burst's light falling off the ground as
 * the inverse square and the cosine say, through the scene's light component and the engine's clustered lights.
 */
class FireworksGpuTest {

    private static final double DT = 1 / 60.0;
    private Stage stage;

    @AfterEach
    void stop() {
        if (stage != null) {
            int errors = stage.validationErrors();
            stage.close();
            stage = null;
            assertThat(errors).as("validation errors").isZero();
        }
    }

    private Stage stage() {
        stage = new Stage(64, 64, true, 16, Stage.Look.LINEAR);
        return stage;
    }

    /** The live particles of {@code effect}'s emitter {@code emitter}, by spawn index: slot offsets, -1 none. */
    private int[] live(Stage s, long effect, int emitter, int capacity) {
        int first = s.particles.first(effect, emitter);
        int[] at = new int[capacity];
        Arrays.fill(at, -1);
        for (int i = 0; i < capacity; i++) {
            float age = s.particle(first, i, ParticleLayout.AGE);
            float life = s.particle(first, i, ParticleLayout.LIFETIME);
            int flags = s.particleInt(first, i, ParticleLayout.FLAGS);
            if (life > 0 && age < life && (flags & 1) == 0) {
                int index = s.particleInt(first, i, ParticleLayout.SPAWN_INDEX);
                assertThat(index).as("spawn index").isBetween(0, capacity - 1);
                assertThat(at[index]).as("star " + index + " once").isEqualTo(-1);
                at[index] = i;
            }
        }
        return at;
    }

    /** Recovers a particle's velocity at birth from its velocity now, its age and drag k (no wind, gravity on). */
    private static double[] birthVelocity(Stage s, int first, int i, double k) {
        double age = s.particle(first, i, ParticleLayout.AGE);
        double[] v0 = new double[3];
        for (int a = 0; a < 3; a++) {
            double v = s.particle(first, i, ParticleLayout.VELOCITY + 4L * a);
            double terminal = (a == 1 ? -Ballistics.G : 0) / k;
            v0[a] = terminal + (v - terminal) * Math.exp(k * age);
        }
        return v0;
    }

    @Test
    void starsLeaveTheBurstOnceEachAlongTheirPatternsDirections() {
        Stage s = stage();
        record Case(Pattern.Layout layout, int n, float tilt, float turn) {
        }
        Case[] cases = {new Case(Pattern.Layout.SPHERE, 81, 0, 0.4f), new Case(Pattern.Layout.RING, 36, 0.7f, 1.3f),
            new Case(Pattern.Layout.PALM, 8, 0.6f, 0.2f)};
        long[] ids = new long[cases.length];
        for (int c = 0; c < cases.length; c++) {
            Case cs = cases[c];
            EffectData data = EffectData.builder().emitter("stars", e -> e.capacity(cs.n())
                .spawn(Spawn.burst(0, cs.n()))
                .init(Module.of("fireworks.pattern").with("kind", cs.layout().ordinal()).with("count", cs.n())
                        .with("speed", 30).with("tilt", cs.tilt()).with("turn", cs.turn()),
                    Init.lifetime(Bind.constant(10)))
                .update(Forces.gravity(), Forces.drag(0.8f))).build(FireworkModules.MODULES.registry());
            ids[c] = s.effects.play(data, 100 * c, 200, 0).id();
        }
        s.frames(3, DT);
        float[] expected = new float[3];
        for (int c = 0; c < cases.length; c++) {
            Case cs = cases[c];
            int[] at = live(s, ids[c], 0, cs.n());
            int first = s.particles.first(ids[c], 0);
            for (int i = 0; i < cs.n(); i++) {
                assertThat(at[i]).as(cs.layout() + " star " + i + " flying").isNotNegative();
                double[] v0 = birthVelocity(s, first, at[i], 0.8);
                double speed = Math.sqrt(v0[0] * v0[0] + v0[1] * v0[1] + v0[2] * v0[2]);
                assertThat(speed).isCloseTo(30, within(0.01));
                Pattern.direction(cs.layout(), i, cs.n(), cs.tilt(), cs.turn(), 0, expected);
                for (int a = 0; a < 3; a++) {
                    assertThat(v0[a] / speed).as(cs.layout() + " star " + i + " axis " + a)
                        .isCloseTo(expected[a], within(2e-4));
                }
            }
        }
        // a whole shell's burst: its stars all there, symmetric about the shell's own velocity (jittered a little)
        FireworkEffects fx = s.fireworks.shells();
        Shell shell = Shell.of(Pattern.PEONY, Colorant.STRONTIUM_RED, 4);
        ShellDesign d = fx.design(shell);
        Effect burst = s.effects.play(fx.burst(shell), 0, 300, 400).seed(5).set(FireworkEffects.VELOCITY_X, 3);
        s.frames(2, DT);
        int[] at = live(s, burst.id(), 0, d.stars());
        int first = s.particles.first(burst.id(), 0);
        double[] sum = new double[3];
        for (int i = 0; i < d.stars(); i++) {
            assertThat(at[i]).as("peony star " + i).isNotNegative();
            double[] v0 = birthVelocity(s, first, at[i], d.drag());
            v0[0] -= 3;
            double speed = Math.sqrt(v0[0] * v0[0] + v0[1] * v0[1] + v0[2] * v0[2]);
            assertThat(speed).isCloseTo(d.starSpeed(), within(0.05 * d.starSpeed()));
            for (int a = 0; a < 3; a++) {
                sum[a] += v0[a] / speed;
            }
        }
        assertThat(Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]) / d.stars())
            .as("the mean direction of the stars").isLessThan(0.03);
    }

    @Test
    void shellsAndStarsFlyAsTheClosedFormSaysThroughGravityDragAndWind() {
        Stage s = stage();
        s.effects.wind(6, 0, -3);
        double[] w = {6, 0, -3};
        FireworkEffects fx = s.fireworks.shells();
        Shell shell = Shell.of(Pattern.PEONY, Colorant.BARIUM_GREEN, 5);
        ShellDesign d = fx.design(shell);
        double[] v0 = {4, d.launchSpeed(), -2};
        double[] site = {20, 0, -50};
        Effect lift = s.effects.play(fx.lift(shell), site[0], site[1], site[2]).seed(3)
            .set(FireworkEffects.VELOCITY_X, (float) v0[0]).set(FireworkEffects.VELOCITY_Y, (float) v0[1])
            .set(FireworkEffects.VELOCITY_Z, (float) v0[2]).set(FireworkEffects.FUSE, 30);
        // and a burst's stars about their centre, moving as one shell would
        double[] vb = {5, 2, 0};
        double[] at = {0, 150, 0};
        Effect burst = s.effects.play(fx.burst(shell), at[0], at[1], at[2]).seed(4)
            .set(FireworkEffects.VELOCITY_X, (float) vb[0]).set(FireworkEffects.VELOCITY_Y, (float) vb[1])
            .set(FireworkEffects.VELOCITY_Z, (float) vb[2]);
        for (int check = 0; check < 3; check++) {
            s.frames(check == 0 ? 10 : 35, DT); // to 1.33 s: every star still burning (none compacted away)
            int first = s.particles.first(lift.id(), 0);
            double age = s.particle(first, 0, ParticleLayout.AGE);
            assertThat(age).isGreaterThan(0);
            for (int a = 0; a < 3; a++) {
                double x = s.particles.origin(lift.id(), a) + s.particle(first, 0, ParticleLayout.POSITION + 4L * a);
                double expected = Ballistics.position(a, site[a], v0[a], d.shellDrag(), w[a], age);
                assertThat(x).as("the shell at %.2f s, axis %d", age, a)
                    .isCloseTo(expected, within(0.02 + 1e-4 * Math.abs(expected)));
                double v = s.particle(first, 0, ParticleLayout.VELOCITY + 4L * a);
                assertThat(v).isCloseTo(Ballistics.velocity(a, v0[a], d.shellDrag(), w[a], age), within(0.01));
            }
            // the stars' centre where the closed form puts a shell of the burst's velocity, through the wind
            int[] live = live(s, burst.id(), 0, d.stars());
            int stars = s.particles.first(burst.id(), 0);
            double[] centre = new double[3];
            double meanAge = 0;
            int n = 0;
            for (int i = 0; i < d.stars(); i++) {
                if (live[i] >= 0) {
                    n++;
                    meanAge += s.particle(stars, live[i], ParticleLayout.AGE);
                    for (int a = 0; a < 3; a++) {
                        centre[a] += s.particles.origin(burst.id(), a)
                            + s.particle(stars, live[i], ParticleLayout.POSITION + 4L * a);
                    }
                }
            }
            assertThat(n).isEqualTo(d.stars());
            meanAge /= n;
            for (int a = 0; a < 3; a++) {
                double expected = Ballistics.position(a, at[a], vb[a], d.drag(), w[a], meanAge);
                // the jittered directions and speeds leave the centre within a few per cent of the radius flown
                double flown = Ballistics.position(0, 0, d.starSpeed(), d.drag(), 0, meanAge);
                assertThat(centre[a] / n).as("the stars' centre at %.2f s, axis %d", meanAge, a)
                    .isCloseTo(expected, within(0.03 * flown + 0.02));
            }
        }
    }

    @Test
    void aDisplaysShellBurstsAtItsApexWhereItsStarsBegin() {
        Stage s = stage();
        Shell shell = Shell.of(Pattern.RING, Colorant.COPPER_BLUE, 4);
        long site = s.scene.create();
        s.scene.setTranslation(site, -30, 5, -200);
        s.scene.update();
        s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(77)
            .cue(new FireworkShow.Cue(0.2, shell, 0, 0, Math.toRadians(8), 0.5)).build()));
        ShellDesign d = s.fireworks.shells().design(shell);
        double vy = d.launchSpeed() * Math.cos(Math.toRadians(8));
        double fuse = Ballistics.apexTime(vy, d.shellDrag());
        double apexY = 5 + Ballistics.apexHeight(vy, d.shellDrag());
        double lastShellY = 0;
        double lastShellVy = 0;
        int frames = 0;
        while (s.fireworks.bursts() == 0 && frames < 2000) {
            s.frames(1, DT);
            frames++;
            long lift = s.fireworks.lastLift();
            if (lift >= 0 && s.particles.instance(lift, 0) >= 0) {
                int first = s.particles.first(lift, 0);
                if (s.particle(first, 0, ParticleLayout.AGE) < s.particle(first, 0, ParticleLayout.LIFETIME)) {
                    lastShellY = s.particles.origin(lift, 1) + s.particle(first, 0, ParticleLayout.POSITION + 4);
                    lastShellVy = s.particle(first, 0, ParticleLayout.VELOCITY + 4);
                }
            }
        }
        // the burst the frame the apex came: 0.2 s in, then the fuse
        assertThat(s.fireworks.time()).isBetween(0.2 + fuse, 0.2 + fuse + DT + 1e-9);
        // the shell's last frame in the air: a frame from its apex, barely rising
        assertThat(lastShellY).isCloseTo(apexY, within(0.5));
        assertThat(lastShellVy).isBetween(-0.5, 2.0);
        // the stars start there: their centre where the burst's closed form puts it, from the apex
        s.frames(3, DT);
        long burst = s.fireworks.lastBurst();
        assertThat(s.particles.instance(burst, 0)).as("the burst's stars placed").isNotNegative();
        int[] live = live(s, burst, 0, d.stars());
        int first = s.particles.first(burst, 0);
        double y = 0;
        double age = 0;
        for (int i = 0; i < d.stars(); i++) {
            assertThat(live[i]).isNotNegative();
            y += s.particles.origin(burst, 1) + s.particle(first, live[i], ParticleLayout.POSITION + 4);
            age += s.particle(first, live[i], ParticleLayout.AGE);
        }
        age /= d.stars();
        assertThat(y / d.stars()).isCloseTo(Ballistics.position(1, apexY, 0, d.drag(), 0, age), within(0.05));
    }

    /** {@code frames} frames of a show of seed {@code seed}: the bytes of its last burst's stars. */
    private static byte[] run(int seed, int frames) {
        try (Stage s = new Stage(64, 64, true, 16, Stage.Look.LINEAR)) {
            long site = s.scene.create();
            s.scene.update();
            s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(seed)
                .cue(0, new Shell(Pattern.CROSSETTE, Colorant.SODIUM_YELLOW, Colorant.BARIUM_GREEN, 3), 0, 0)
                .cue(0, new Shell(Pattern.CHRYSANTHEMUM, Colorant.PURPLE, null, 3), 30, 0).build()));
            s.frames(frames, DT);
            long burst = s.fireworks.lastBurst();
            assertThat(burst).isNotNegative();
            byte[] stars = s.particleBytes(s.particles.first(burst, 0), 81);
            int split = s.fireworks.shells().burst(new Shell(Pattern.CROSSETTE, Colorant.SODIUM_YELLOW,
                Colorant.BARIUM_GREEN, 3)).emitter("split");
            assertThat(split).isPositive();
            assertThat(s.validationErrors()).isZero();
            return stars;
        }
    }

    @Test
    void theSameSeedFliesTheSameStarsAndAnotherOthers() {
        int frames = (int) (5.5 / DT);
        byte[] a = run(11, frames);
        byte[] b = run(11, frames);
        byte[] c = run(12, frames);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void aBurstLightsTheGroundByTheInverseSquareAndTheCosine() {
        Stage s = stage();
        s.settings.set(FireworksSettings.SMOKE, 0f);
        int ground = s.material(0.5f, 0.5f, 0.5f, 1, 0, 0, 0);
        s.place(s.box, ground, 0, -0.05, 0, 0, 0, 400, 0.05f, 400);
        s.lookDown(0, 40, 0, 100);
        long site = s.scene.create();
        s.scene.update();
        // the ground without the burst (what the renderer's constant sky gives it), taken from what it shows lit
        s.frames(3, DT);
        float[][] dark = new float[64 * 64][];
        for (int i = 0; i < 64 * 64; i++) {
            dark[i] = s.color(i % 64, i / 64);
        }
        Shell shell = Shell.of(Pattern.PEONY, Colorant.MAGNESIUM_WHITE, 1.5); // bursting 50 m up
        s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(1)
            .cue(0, shell, 0, 0).build()));
        ShellDesign d = s.fireworks.shells().design(shell);
        double fuse = Ballistics.apexTime(d.launchSpeed(), d.shellDrag());
        s.frames((int) ((fuse + 0.5) / DT), DT);
        s.frames(2, 0); // held: the light where and as bright as it is
        ComponentStore<LightSource> lights = s.scene.components().store(LightSource.TYPE);
        assertThat(lights.size()).isEqualTo(4);
        double[][] at = new double[4][];
        double[] intensities = new double[4];
        double total = 0;
        for (int i = 0; i < 4; i++) {
            LightSource light = lights.value(i);
            var p = s.scene.world(lights.node(i)).translation();
            at[i] = new double[] {p.x(), p.y(), p.z()};
            intensities[i] = light.intensity() * Spectra.luminance(light.red(), light.green(), light.blue());
            total += intensities[i];
        }
        assertThat(total).isCloseTo(d.light(s.fireworks.time() - fuse), within(0.01 * total));
        // each pixel's ground point (the camera 40 m up looking down, 100 degrees across): its expected luminance,
        // a Lambertian ground of albedo 0.5 under I cos(theta) / d²
        double ratioSum = 0;
        double ratioSquares = 0;
        int n = 0;
        double nearest = Double.MAX_VALUE;
        double farthest = 0;
        for (int py = 4; py < 64; py += 8) {
            for (int px = 4; px < 64; px += 8) {
                double half = 40 * Math.tan(Math.toRadians(50));
                double gx = (2 * (px + 0.5) / 64 - 1) * half;
                double gz = -(1 - 2 * (py + 0.5) / 64) * half;
                double expected = 0;
                double d2 = 0;
                for (int k = 0; k < 4; k++) {
                    double dx = at[k][0] - gx;
                    double dy = at[k][1];
                    double dz = at[k][2] - gz;
                    double r2 = dx * dx + dy * dy + dz * dz;
                    expected += 0.5 / Math.PI * intensities[k] * (dy / Math.sqrt(r2)) / r2;
                    d2 += r2 / 4;
                }
                float[] c = s.color(px, py);
                float[] d0 = dark[py * 64 + px];
                double measured = Spectra.luminance(c[0] - d0[0], c[1] - d0[1], c[2] - d0[2]);
                double ratio = measured / expected;
                ratioSum += ratio;
                ratioSquares += ratio * ratio;
                n++;
                nearest = Math.min(nearest, Math.sqrt(d2));
                farthest = Math.max(farthest, Math.sqrt(d2));
            }
        }
        double mean = ratioSum / n;
        double spread = Math.sqrt(Math.max(0, ratioSquares / n - mean * mean));
        System.out.printf("ground lit: %d points from %.1f to %.1f m from the light, measured / expected %.4f +- %.4f%n",
            n, nearest, farthest, mean, spread);
        assertThat(mean).as("the ground's luminance against I cos / d² albedo / pi").isCloseTo(1, within(0.1));
        assertThat(spread).as("the same ratio everywhere: the falloff's shape").isLessThan(0.03);
    }

    @Test
    void thePluginPlaysTheWorldsShowsWithTheFireworksKernel() {
        FireworksPlugin plugin = new FireworksPlugin();
        org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(plugin::sync);
        com.truesim.engine.app.Engine engine;
        try {
            engine = com.truesim.engine.app.Engine.create(new com.truesim.engine.app.Application() {
                @Override
                public void configure(com.truesim.engine.app.AppConfig app) {
                    app.use(new com.truesim.engine.graph.Rendering().headless(true),
                        new com.truesim.effects.particles.scene.EffectsPlugin(), plugin);
                }
            });
        } catch (com.truesim.engine.app.StartupFailure e) {
            org.assertj.core.api.Assumptions.assumeThat(e).as("no Vulkan available: " + e.reason()).isNull();
            throw e;
        }
        try (engine) {
            var fx = engine.get(com.truesim.effects.particles.scene.EffectsPlugin.KEY);
            com.truesim.scene.core.SceneGraph world =
                new com.truesim.scene.core.SceneGraph(new com.truesim.common.state.ChangeClock());
            com.truesim.scene.effects.Effects playing = fx.effects(world);
            FireworksSync sync = engine.get(FireworksPlugin.KEY).play(playing);
            org.assertj.core.api.Assertions.assertThatIllegalStateException().isThrownBy(() -> plugin.play(playing));
            assertThat(plugin.sync()).isSameAs(sync);
            assertThat(engine.settings().get(FireworksSettings.LIGHTS_PER_BURST)).isEqualTo(4);
            long site = world.create();
            world.update();
            world.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(3)
                .cue(0, Shell.of(Pattern.STROBE, Colorant.MAGNESIUM_WHITE, 3), 0, 0).build()));
            for (int f = 0; f < 10; f++) {
                engine.step();
            }
            assertThat(sync.launched()).isEqualTo(1);
            assertThat(fx.particles().instance(sync.lastLift(), 0)).as("the lift on the fireworks' kernel")
                .isNotNegative();
        }
        assertThat(plugin).isNotNull();
    }
}
