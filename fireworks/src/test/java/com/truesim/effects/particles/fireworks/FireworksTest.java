package com.truesim.effects.particles.fireworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.core.ComponentStore;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Effects;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fireworks without a GPU: motion in closed form, colours from spectra, the patterns' layouts, shows as data and
 * files, and the sync firing, bursting, lighting and sounding shells where and when the physics says.
 */
class FireworksTest {

    private final EngineSettings settings = new EngineSettings();

    // ------------------------------------------------------------------------------------------ motion

    @Test
    void motionUnderGravityAndDragIsTheClosedFormItIntegratesTo() {
        // a fine explicit integration of dv/dt = g - k (v - w) agrees with the closed form
        double k = 0.4;
        double[] w = {3, 0, -2};
        double[] x = {1, 2, 3};
        double[] v = {5, 40, -1};
        double[] x0 = x.clone();
        double[] v0 = v.clone();
        double h = 1e-5;
        int steps = 300_000; // 3 s
        for (int i = 0; i < steps; i++) {
            for (int a = 0; a < 3; a++) {
                double acc = (a == 1 ? -Ballistics.G : 0) - k * (v[a] - w[a]);
                v[a] += acc * h;
                x[a] += v[a] * h;
            }
        }
        for (int a = 0; a < 3; a++) {
            assertThat(Ballistics.position(a, x0[a], v0[a], k, w[a], 3)).isCloseTo(x[a], within(2e-3));
            assertThat(Ballistics.velocity(a, v0[a], k, w[a], 3)).isCloseTo(v[a], within(1e-3));
        }
        // without drag: the parabola
        assertThat(Ballistics.position(1, 0, 20, 0, 0, 2)).isCloseTo(40 - 0.5 * Ballistics.G * 4, within(1e-12));
    }

    @Test
    void theApexIsWhereTheShellStopsRisingAndTheLaunchSpeedReachesTheHeight() {
        double k = 0.12;
        double v = 80;
        double t = Ballistics.apexTime(v, k);
        assertThat(Ballistics.velocity(1, v, k, 0, t)).isCloseTo(0, within(1e-9));
        assertThat(Ballistics.position(1, 0, v, k, 0, t)).isCloseTo(Ballistics.apexHeight(v, k), within(1e-9));
        assertThat(Ballistics.apexTime(v, k, 0)).isCloseTo(t, within(1e-12));
        // rising air delays the apex; the closed form's velocity is zero there too
        double rising = Ballistics.apexTime(v, k, 2);
        assertThat(rising).isGreaterThan(t);
        assertThat(Ballistics.velocity(1, v, k, 2, rising)).isCloseTo(0, within(1e-9));
        // drag-free: v / g and v² / 2g
        assertThat(Ballistics.apexTime(30, 0)).isCloseTo(30 / Ballistics.G, within(1e-12));
        assertThat(Ballistics.apexHeight(30, 0)).isCloseTo(900 / (2 * Ballistics.G), within(1e-12));
        for (double height : new double[] {10, 99, 330, 800}) {
            double speed = Ballistics.launchSpeed(height, k);
            assertThat(Ballistics.apexHeight(speed, k)).isCloseTo(height, within(1e-6 * height));
        }
        // drag makes the shell need more speed than the parabola
        assertThat(Ballistics.launchSpeed(100, 0.12)).isGreaterThan(Math.sqrt(2 * Ballistics.G * 100));
        // a star from a burst reaches the radius in its lifetime against drag
        double s = Ballistics.burstSpeed(30, 0.9, 2);
        assertThat(Ballistics.position(0, 0, s, 0.9, 0, 2)).isCloseTo(30, within(1e-9));
    }

    // ------------------------------------------------------------------------------------------ colours

    @Test
    void colorantsAreTheColoursOfTheirSpectraAtUnitLuminance() {
        for (Colorant c : Colorant.values()) {
            assertThat(Spectra.luminance(c.rgb(0), c.rgb(1), c.rgb(2))).as(c.name()).isCloseTo(1, within(1e-3));
            for (int k = 0; k < 3; k++) {
                assertThat(c.rgb(k)).as(c.name()).isGreaterThanOrEqualTo(0);
            }
        }
        // red is red, green green, blue blue, yellow has no blue, charcoal is warm, magnesium nearly white
        assertThat(Colorant.STRONTIUM_RED.rgb(0)).isGreaterThan(5 * Colorant.STRONTIUM_RED.rgb(1));
        assertThat(Colorant.BARIUM_GREEN.rgb(1)).isGreaterThan(Colorant.BARIUM_GREEN.rgb(0));
        assertThat(Colorant.BARIUM_GREEN.rgb(1)).isGreaterThan(Colorant.BARIUM_GREEN.rgb(2));
        assertThat(Colorant.COPPER_BLUE.rgb(2)).isGreaterThan(5 * Colorant.COPPER_BLUE.rgb(0));
        assertThat(Colorant.SODIUM_YELLOW.rgb(2)).isLessThan(0.1f * Colorant.SODIUM_YELLOW.rgb(0));
        assertThat(Colorant.CHARCOAL_GOLD.rgb(0)).isGreaterThan(2 * Colorant.CHARCOAL_GOLD.rgb(2));
        float[] white = Colorant.MAGNESIUM_WHITE.rgb(0) > 0 ? new float[] {Colorant.MAGNESIUM_WHITE.rgb(0),
            Colorant.MAGNESIUM_WHITE.rgb(1), Colorant.MAGNESIUM_WHITE.rgb(2)} : null;
        assertThat(white[2] / white[0]).isBetween(0.45f, 1.1f);
        // the matching functions' fit: y-bar peaks at 555 nm near 1, and a 6500 K body is near D65 white
        assertThat(Spectra.yBar(555)).isCloseTo(1, within(0.02));
        float[] d65 = Spectra.color(new double[0], 6500, 1);
        assertThat((double) d65[0] / d65[2]).isCloseTo(1, within(0.08));
        // a single line out of the gamut is mixed with white, its luminance kept
        float[] line = Spectra.color(new double[] {450, 2, 1}, 0, 0);
        assertThat(Math.min(line[0], Math.min(line[1], line[2]))).isCloseTo(0, within(1e-6f));
        assertThat(Spectra.luminance(line[0], line[1], line[2])).isCloseTo(1, within(1e-3));
        assertThat(Colorant.of("strontium")).isEqualTo(Colorant.STRONTIUM_RED);
        assertThat(Colorant.of("COPPER_BLUE")).isEqualTo(Colorant.COPPER_BLUE);
        assertThat(Colorant.of(Colorant.PURPLE.key())).isEqualTo(Colorant.PURPLE);
        assertThatThrownBy(() -> Colorant.of("lithium")).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------ patterns

    @Test
    void patternsLayTheirStarsOutSymmetrically() {
        float[] d = new float[3];
        for (int n : new int[] {12, 81, 400}) {
            double sx = 0;
            double sy = 0;
            double sz = 0;
            double nearest = Double.MAX_VALUE;
            double farthest = 0;
            List<float[]> dirs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Pattern.direction(Pattern.Layout.SPHERE, i, n, 0, 0.7f, 0, d);
                assertThat(Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])).isCloseTo(1, within(1e-5));
                sx += d[0];
                sy += d[1];
                sz += d[2];
                dirs.add(d.clone());
            }
            // the golden-angle sphere: the directions sum to (almost) nothing, and every star has a neighbour about
            // as far as the even spacing on the sphere says (4 pi / n per star)
            assertThat(Math.sqrt(sx * sx + sy * sy + sz * sz) / n).as("mean of " + n).isLessThan(0.03);
            for (int i = 0; i < n; i++) {
                double best = Double.MAX_VALUE;
                for (int j = 0; j < n; j++) {
                    if (j != i) {
                        best = Math.min(best, angle(dirs.get(i), dirs.get(j)));
                    }
                }
                nearest = Math.min(nearest, best);
                farthest = Math.max(farthest, best);
            }
            double spacing = Math.sqrt(4 * Math.PI / n);
            assertThat(nearest).as("closest neighbours of " + n).isGreaterThan(0.45 * spacing);
            assertThat(farthest).as("loneliest star of " + n).isLessThan(1.4 * spacing);
        }
        // a ring: every star in the plane its tilt and turn give, evenly round it
        float tilt = 0.6f;
        float turn = 1.1f;
        double[] normal = {Math.sin(tilt) * Math.cos(turn), Math.cos(tilt), Math.sin(tilt) * Math.sin(turn)};
        float[] first = new float[3];
        Pattern.direction(Pattern.Layout.RING, 0, 36, tilt, turn, 0, first);
        for (int i = 0; i < 36; i++) {
            Pattern.direction(Pattern.Layout.RING, i, 36, tilt, turn, 0, d);
            assertThat(d[0] * normal[0] + d[1] * normal[1] + d[2] * normal[2]).isCloseTo(0, within(1e-5));
            assertThat(angle(first, d.clone())).isCloseTo(Math.min(i, 36 - i) * 2 * Math.PI / 36, within(1e-4));
        }
        // a palm's arms: all at the cone's angle from the vertical, evenly round it
        for (int i = 0; i < 7; i++) {
            Pattern.direction(Pattern.Layout.PALM, i, 7, 0.6f, 0.3f, 0, d);
            assertThat(Math.acos(d[1])).isCloseTo(0.6, within(1e-5));
            assertThat(Math.atan2(d[2], d[0])).isCloseTo(Math.IEEEremainder(0.3 + 2 * Math.PI * i / 7, 2 * Math.PI),
                within(1e-5));
        }
        // a cross: four horizontal arms at right angles
        float[] a = new float[3];
        Pattern.direction(Pattern.Layout.CROSS, 0, 4, 0, 0, 0.3f, a);
        for (int i = 1; i < 4; i++) {
            Pattern.direction(Pattern.Layout.CROSS, i, 4, 0, 0, 0.3f, d);
            assertThat(d[1]).isZero();
            assertThat(angle(a, d.clone())).isCloseTo(i == 2 ? Math.PI : Math.PI / 2, within(1e-5));
        }
        // star counts grow with the calibre's square; a palm has a few arms
        assertThat(Pattern.PEONY.stars(3)).isEqualTo(81);
        assertThat(Pattern.PEONY.stars(6)).isEqualTo(4 * 81);
        assertThat(Pattern.PALM.stars(4)).isEqualTo(9);
    }

    private static double angle(float[] a, float[] b) {
        double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        return Math.acos(Math.max(-1, Math.min(1, dot)));
    }

    // ------------------------------------------------------------------------------------------ designs

    @Test
    void aShellsDesignFollowsItsCalibreAndTheSettings() {
        ShellDesign d = ShellDesign.of(Shell.of(Pattern.PEONY, Colorant.STRONTIUM_RED, 4), settings);
        assertThat(d.height()).isCloseTo(4 * 33, within(1e-4));
        assertThat(d.radius()).isCloseTo(40, within(1e-4));
        assertThat(Ballistics.apexHeight(d.launchSpeed(), d.shellDrag())).isCloseTo(d.height(), within(1e-6));
        assertThat(Ballistics.position(0, 0, d.starSpeed(), d.drag(), 0, d.lifetime())).isCloseTo(40, within(1e-9));
        // the light: the stars' intensities, more at ignition's flare, the flash on top at first, nothing once burnt out
        assertThat(d.light(0.5)).isCloseTo(d.stars() * d.starCandela() * ShellDesign.burn(0.5 / d.lifetime()),
            within(0.05 * d.light(0.5)));
        assertThat(d.light(0.01)).isGreaterThan(d.flashCandela());
        assertThat(d.light(d.lightEnd() + 0.01)).isZero();
        assertThat(d.light(-0.1)).isZero();
        // brighter stars, a brighter burst
        settings.set(FireworksSettings.STAR_INTENSITY, 2f);
        ShellDesign brighter = ShellDesign.of(d.shell(), settings);
        assertThat(brighter.light(0.5) - brighter.flash(0.5)).isCloseTo(2 * (d.light(0.5) - d.flash(0.5)),
            within(1e-6));
        // a round sprite's luminance gives the intensity over its area: L pi s² / 8 = I
        double l = ShellDesign.spriteLuminance(400, 0.5);
        assertThat(l * Math.PI * 0.25 / 8).isCloseTo(400, within(1e-9));
        // the light's colour is its colorants' mixed
        float[] rgb = new float[3];
        d.light(1, rgb);
        assertThat(rgb[0]).isCloseTo(Colorant.STRONTIUM_RED.rgb(0), within(1e-5f));
        assertThatThrownBy(() -> Shell.of(Pattern.PEONY, Colorant.STRONTIUM_RED, 0.5))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shellsOfOneTypeShareTheirEffectsUntilTheSettingsChange() {
        FireworkEffects fx = new FireworkEffects(settings);
        Shell red = Shell.of(Pattern.CHRYSANTHEMUM, Colorant.STRONTIUM_RED, 5);
        Shell green = Shell.of(Pattern.CHRYSANTHEMUM, Colorant.BARIUM_GREEN, 5);
        EffectData burst = fx.burst(red);
        assertThat(fx.burst(green)).isSameAs(burst);
        assertThat(fx.lift(green)).isSameAs(fx.lift(red));
        assertThat(burst.emitters().get(0)).isNotNull();
        assertThat(burst.emitter(FireworkEffects.STARS)).isZero();
        settings.set(FireworksSettings.SMOKE, 0f);
        assertThat(fx.burst(red)).isNotSameAs(burst);
        // every pattern makes its effects
        for (Pattern p : Pattern.values()) {
            assertThat(fx.burst(new Shell(p, Colorant.COPPER_BLUE, Colorant.SODIUM_YELLOW, 3))).isNotNull();
        }
    }

    // ------------------------------------------------------------------------------------------ shows

    @Test
    void showsAreTheSameFromTheSameSeedAndRoundTripThroughTheirFiles() {
        FireworkShow a = FireworkShow.random(42, 60, 1.5, 120);
        FireworkShow b = FireworkShow.random(42, 60, 1.5, 120);
        FireworkShow c = FireworkShow.random(43, 60, 1.5, 120);
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.size()).isGreaterThan(20);
        for (int i = 1; i < a.size(); i++) {
            assertThat(a.cue(i).time()).isGreaterThanOrEqualTo(a.cue(i - 1).time());
        }
        FireworkShow finale = FireworkShow.finale(7, 10, 20, 60, 100);
        assertThat(finale.size()).isEqualTo(60);
        assertThat(finale.lastLaunch()).isBetween(10.0, 30.0);
        // the file: the same show back, to the file's precision
        FireworkShow read = FireworkShow.parse(a.text().getBytes(java.nio.charset.StandardCharsets.UTF_8), "a");
        assertThat(read.seed()).isEqualTo(42);
        assertThat(read.size()).isEqualTo(a.size());
        for (int i = 0; i < a.size(); i++) {
            assertThat(read.cue(i).shell()).isEqualTo(a.cue(i).shell());
            assertThat(read.cue(i).time()).isCloseTo(a.cue(i).time(), within(1e-4));
            assertThat(read.cue(i).tilt()).isCloseTo(a.cue(i).tilt(), within(1e-4));
        }
        String text = "seed 3\n# a comment\n0.5 ring barium - 4 1 2 0 0\n0 peony strontium copper 3 0 0 5 90\n";
        FireworkShow small = FireworkShow.parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), "small");
        assertThat(small.cue(0).shell()).isEqualTo(new Shell(Pattern.PEONY, Colorant.STRONTIUM_RED,
            Colorant.COPPER_BLUE, 3));
        assertThat(small.cue(1).shell().second()).isNull();
        assertThatThrownBy(() -> FireworkShow.parse("0 peony red - 3 0 0 0 0".getBytes(), "x"))
            .hasMessageContaining("x");
        assertThatThrownBy(() -> FireworkShow.parse("seed 1\n0 peony\n".getBytes(), "y"))
            .hasMessageContaining("line 2");
    }

    @Test
    void showsLoadThroughTheAssetCacheFromTheirFiles(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
        throws Exception {
        FireworkShow show = FireworkShow.random(5, 20, 2, 80);
        java.nio.file.Files.writeString(dir.resolve("night.fireworks"), show.text());
        java.nio.file.Files.writeString(dir.resolve("broken.fireworks"), "0 peony strontium - 3 0 0 0 0\n");
        try (com.truesim.common.jobs.TaskScheduler tasks = new com.truesim.common.jobs.TaskScheduler();
             com.truesim.scene.assets.AssetCache cache = new com.truesim.scene.assets.AssetCache(tasks)) {
            com.truesim.scene.assets.PathAssets paths = new com.truesim.scene.assets.PathAssets(cache);
            paths.mounts().add("shows", com.truesim.scene.assets.PathSource.folder(dir));
            paths.importers().add(FireworkAssets.SHOW, "fireworks", FireworkAssets.IMPORTER);
            var ref = paths.ref(FireworkAssets.SHOW, "shows://night.fireworks");
            var broken = paths.ref(FireworkAssets.SHOW, "shows://broken.fireworks");
            cache.acquire(ref);
            cache.acquire(broken);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while ((cache.state(ref) != com.truesim.scene.assets.AssetState.LOADED
                || cache.state(broken) != com.truesim.scene.assets.AssetState.FAILED) && System.nanoTime() < deadline) {
                tasks.drainCompleted();
                Thread.sleep(1);
            }
            FireworkShow loaded = cache.get(ref);
            assertThat(loaded.seed()).isEqualTo(5);
            assertThat(loaded.size()).isEqualTo(show.size());
            assertThat(loaded.bytes()).isPositive();
            assertThat(cache.state(broken)).isEqualTo(com.truesim.scene.assets.AssetState.FAILED);
            assertThat(cache.failure(broken)).hasMessageContaining("no seed");
            assertThat(FireworkDisplay.of(loaded).show()).isSameAs(loaded);
            assertThat(FireworkDisplay.of(loaded, 3).delay()).isEqualTo(3);
            assertThat(FireworkDisplay.of(loaded).toString()).contains("fireworks");
        }
        assertThatThrownBy(() -> FireworkDisplay.of(show, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSettingsAreListed() {
        com.truesim.engine.settings.SettingRegistry registry = new com.truesim.engine.settings.SettingRegistry();
        FireworksSettings.register(registry);
        assertThat(registry.all("fireworks")).hasSize(20);
    }

    // ------------------------------------------------------------------------------------------ the sync

    private final SceneGraph scene = new SceneGraph(new ChangeClock());
    private final Effects effects = new Effects(scene);

    /** Runs the sync and the scene {@code frames} frames of {@code dt}. */
    private void run(FireworksSync sync, int frames, double dt) {
        for (int i = 0; i < frames; i++) {
            effects.advance(dt);
            sync.update(dt);
            scene.update();
        }
    }

    @Test
    void shellsBurstAtTheirApexAndLightTheirStarsCentre() {
        effects.wind(4, 0, -1);
        Shell shell = Shell.of(Pattern.PEONY, Colorant.BARIUM_GREEN, 5);
        long site = scene.create();
        scene.setTranslation(site, 10, 2, -300);
        FireworksSync sync = new FireworksSync(effects, settings);
        scene.update();
        scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(5)
            .cue(new FireworkShow.Cue(0.5, shell, 3, 4, 0, 0)).build()));
        ShellDesign d = sync.shells().design(shell);
        double fuse = Ballistics.apexTime(d.launchSpeed(), d.shellDrag());
        double dt = 1 / 120.0;
        double burstTime = -1;
        double last = 0;
        for (int i = 0; i < 2000 && burstTime < 0; i++) {
            run(sync, 1, dt);
            if (sync.bursts() == 1) {
                burstTime = sync.time();
            }
            last = sync.time();
        }
        // the burst comes the frame its apex is reached: 0.5 s in, then the fuse
        assertThat(burstTime).isBetween(0.5 + fuse, 0.5 + fuse + dt + 1e-9);
        assertThat(sync.launched()).isEqualTo(1);
        // its sounds: the launch at the mortar, the burst at the apex, the apex at the design's height above it
        double[] heard = new double[2];
        double[] apex = new double[3];
        sync.sounds().forEachSince(-1, (s, slot, id) -> {
            if (s.kind(slot) == FireworkSounds.Kind.LAUNCH) {
                heard[0] = s.time(slot);
                assertThat(s.position(slot, 0)).isCloseTo(13, within(1e-9));
                assertThat(s.position(slot, 2)).isCloseTo(-296, within(1e-9));
            } else {
                heard[1] = s.time(slot);
                for (int a = 0; a < 3; a++) {
                    apex[a] = s.position(slot, a);
                }
            }
        });
        assertThat(heard[0]).isCloseTo(0.5, within(1e-12));
        assertThat(heard[1]).isCloseTo(0.5 + fuse, within(1e-12));
        assertThat(apex[1]).isCloseTo(2 + d.height(), within(1e-6));
        // the wind carried it downwind on the way up: as the closed form has it
        assertThat(apex[0]).isCloseTo(Ballistics.position(0, 13, 0, d.shellDrag(), 4, fuse), within(1e-9));
        assertThat(apex[0]).isGreaterThan(13 + 1);
        // the light: four point lights about the stars' centre at their distance from it, together as bright as
        // they burn, each reaching to the threshold
        ComponentStore<LightSource> lights = scene.components().store(LightSource.TYPE);
        run(sync, 60, dt);
        assertThat(lights.size()).isEqualTo(4);
        assertThat(sync.lit()).isEqualTo(4);
        double age = sync.time() - (0.5 + fuse);
        double sum = 0;
        double[] mean = new double[3];
        double radius = Ballistics.position(0, 0, d.starSpeed(), d.drag(), 0, age);
        double vx = Ballistics.velocity(0, 0, d.shellDrag(), 4, fuse);
        double cx = Ballistics.position(0, apex[0], vx, d.drag(), 4, age);
        double cy = Ballistics.position(1, apex[1], 0, d.drag(), 0, age);
        double vz = Ballistics.velocity(2, 0, d.shellDrag(), -1, fuse);
        double cz = Ballistics.position(2, apex[2], vz, d.drag(), -1, age);
        for (int i = 0; i < 4; i++) {
            LightSource light = lights.value(i);
            sum += light.intensity();
            assertThat(light.range()).isCloseTo(Math.sqrt(d.light(age) / 4 / 0.002), within(1e-3));
            assertThat(light.green()).isGreaterThan(light.red());
            var p = scene.world(lights.node(i)).translation();
            mean[0] += p.x() / 4;
            mean[1] += p.y() / 4;
            mean[2] += p.z() / 4;
            double dx = p.x() - cx;
            double dy = p.y() - cy;
            double dz = p.z() - cz;
            assertThat(Math.sqrt(dx * dx + dy * dy + dz * dz))
                .as("at the stars' distance from the centre").isCloseTo(radius, within(1e-6 * radius + 1e-6));
        }
        assertThat(sum).isCloseTo(d.light(age), within(1e-6 * d.light(age)));
        assertThat(mean[0]).isCloseTo(cx, within(1e-6));
        assertThat(mean[1]).isCloseTo(cy, within(1e-4));
        assertThat(mean[2]).isCloseTo(cz, within(1e-6));
        // once the stars are out, the light goes
        run(sync, (int) ((d.lightEnd() + 0.1) / dt), dt);
        assertThat(lights.size()).isZero();
        assertThat(sync.flights()).isZero();
        assertThat(last).isGreaterThan(0);
        sync.close();
    }

    @Test
    void soundsReachAListenerLateByTheirDistanceAndCracklesFollowTheBurst() {
        Shell shell = Shell.of(Pattern.CRACKLE, Colorant.TITANIUM_SILVER, 4);
        long site = scene.create();
        FireworksSync sync = new FireworksSync(effects, settings);
        scene.update();
        scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(1)
            .cue(0, shell, 0, 0).build(), 0.25));
        run(sync, 600, 1 / 60.0);
        List<FireworkSounds.Kind> kinds = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        sync.sounds().forEachSince(-1, (s, slot, id) -> {
            kinds.add(s.kind(slot));
            times.add(s.time(slot));
        });
        assertThat(kinds.get(0)).isEqualTo(FireworkSounds.Kind.LAUNCH);
        assertThat(times.get(0)).isCloseTo(0.25, within(1e-12));
        assertThat(kinds.get(1)).isEqualTo(FireworkSounds.Kind.BURST);
        assertThat(kinds.subList(2, kinds.size())).hasSize(FireworksSync.CRACKLE_VOLLEYS)
            .containsOnly(FireworkSounds.Kind.CRACKLE);
        // crackles come as the stars burn out
        ShellDesign d = sync.shells().design(shell);
        assertThat(times.get(2) - times.get(1)).isGreaterThan(d.lifetime() * 0.8);
        // heard 300 m away: a second's delay per 343 m, beyond the burst's height
        FireworkSounds sounds = sync.sounds();
        int burst = 1;
        double distance = sounds.distance(burst, 300, 0, 0);
        assertThat(distance).isGreaterThan(300);
        assertThat(sounds.arrival(burst, 300, 0, 0, 343)).isCloseTo(times.get(1) + distance / 343, within(1e-12));
        List<FireworkSounds.Kind> arriving = new ArrayList<>();
        double heard = sounds.arrival(burst, 300, 0, 0, 343);
        sounds.forEachArriving(300, 0, 0, heard - 1e-6, heard + 1e-6, 343, (s, slot, id) -> arriving.add(s.kind(slot)));
        assertThat(arriving).containsExactly(FireworkSounds.Kind.BURST);
        assertThat(sounds.level(burst)).isCloseTo((float) (140 + 20 * Math.log10(4 / 3.0)), within(1e-4f));
        sync.close();
    }

    @Test
    void displaysStopFiringWhenTheirComponentGoesAndAtMostTheSettingsLightsShine() {
        settings.set(FireworksSettings.MAX_LIGHTS, 12); // three bursts of four
        long site = scene.create();
        FireworksSync sync = new FireworksSync(effects, settings);
        scene.update();
        FireworkShow.Builder b = FireworkShow.builder(9);
        for (int i = 0; i < 8; i++) {
            b.cue(0.01 * i, Shell.of(Pattern.WILLOW, Colorant.CHARCOAL_GOLD, 3), 10 * i, 0);
        }
        b.cue(30, Shell.of(Pattern.PEONY, Colorant.STRONTIUM_RED, 3), 0, 0);
        scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(b.build()));
        run(sync, 300, 1 / 60.0);
        assertThat(sync.launched()).isEqualTo(8);
        assertThat(sync.lit()).isEqualTo(12);
        assertThat(scene.components().store(LightSource.TYPE).size()).isEqualTo(12);
        assertThat(FireworksSync.spread(3)).isEqualTo(2);
        assertThat(FireworksSync.spread(7)).isEqualTo(6);
        scene.components().remove(site, FireworkDisplay.TYPE);
        run(sync, 60 * 40, 1 / 60.0);
        assertThat(sync.launched()).as("the last cue was never fired").isEqualTo(8);
        // paused effects pause the show's clock
        double t = sync.time();
        effects.pause(true);
        sync.update(1);
        assertThat(sync.time()).isEqualTo(t);
        sync.close();
    }
}
