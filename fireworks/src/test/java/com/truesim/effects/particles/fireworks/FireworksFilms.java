package com.truesim.effects.particles.fireworks;

import com.truesim.effects.atmosphere.ephemeris.AstroTime;
import com.truesim.effects.atmosphere.ephemeris.Ephemeris;
import com.truesim.effects.atmosphere.ephemeris.EphemerisConfig;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.post.AutoExposure;
import com.truesim.effects.post.Bloom;
import com.truesim.engine.render.DisplayTransform;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of the fireworks as PNG frames at 30 a second, 1280 x 720, into {@code build/films/fireworks} (or
 * {@code TRUESIM_FIREWORKS_FILMS}): {@code show}, 30 s of a show over an Alpine village square at night (a random show
 * from a seed, then a finale), the bursts lighting the houses, the ground and the smoke, bloom and auto exposure
 * (effects-post) and the display transform, under effects-atmosphere's night sky (stars, the moon, airglow, the Milky
 * Way) on a November night in the Alps; {@code patterns}, a close look at each pattern in turn; {@code smoke}, a ring's
 * smoke close up as it spreads and the bursts after it light it. Each frame is two steps of 1/60 s. Run with {@code TRUESIM_SCREENSHOTS=true ./gradlew :effects-particles-fireworks:test --tests
 * '*FireworksFilms'}; encode with {@code ffmpeg -framerate 30 -i <film>/f%04d.png -c:v libx264 -pix_fmt yuv420p -crf 20
 * <film>.mp4}.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class FireworksFilms {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final double STEP = 1 / 60.0;

    private static Path out(String film) throws IOException {
        String dir = System.getenv("TRUESIM_FIREWORKS_FILMS");
        Path root = dir == null || dir.isBlank() ? Path.of("build", "films", "fireworks") : Path.of(dir);
        Path path = root.resolve(film);
        if (Files.isDirectory(path)) {
            try (var files = Files.list(path)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.delete(f);
                }
            }
        }
        return Files.createDirectories(path);
    }

    /**
     * The look of a night film: physical bloom; auto exposure metering the brighter tenth of the image but its very
     * brightest (the bursts' glow while they burn, else the lit windows, lamps and ground), down fast and up slowly as
     * an eye or a camera does, between 1/64 and 2 per cd/m² (stars' cores burn out to white, the lit village, the smoke
     * and between bursts the moonlit sky's brighter stars stay visible); AgX.
     */
    static void night(Stage s) throws InterruptedException {
        Bloom.SLOT.choose(s.settings, Bloom.PHYSICAL);
        s.settings.set(Bloom.PHYSICAL.<Float>setting("intensity"), 0.05f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("low"), 0.9f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("high"), 0.995f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("minEv"), -6f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("maxEv"), 1f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("speedUp"), 0.8f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("speedDown"), 3f);
        s.settings.set(DisplayTransform.CURVE, "agx");
        s.effects.wind(2.5f, 0, 0.8f);
        // the night: its sky loaded, exposed as AtmosphereExposure would for it, but never below EV100 -2: the stars'
        // cores (thousands of cd/m²) must stay within the half floats of scene colour. The night's luminance: what
        // the moon and the sun (lux at the camera, the atmosphere's) give a ground of albedo 0.18, and the moonless
        // sky's airglow and starlight (some 2e-4 cd/m²)
        s.skyAt(NIGHT);
        s.exposeAt(-2);
        s.loadSky();
        double[] e = new double[3];
        double lux = 0;
        for (int light = 0; light < 2; light++) {
            s.atmosphere.lightAtCamera(light, e);
            lux += Spectra.luminance((float) e[0], (float) e[1], (float) e[2]) / s.atmosphere.sceneUnits();
        }
        double luminance = lux * 0.18 / Math.PI + 2e-4;
        double ev = Math.max(-2, AtmosphereExposure.ev100(luminance));
        System.out.printf(Locale.ROOT, "night: %.3g lux from the moon and sun, %.3g cd/m², AtmosphereExposure asks "
            + "EV100 %.2f, exposed at %.2f%n", lux, luminance, AtmosphereExposure.ev100(luminance), ev);
        s.exposeAt(ev);
        s.frames(4, 0);
    }

    /**
     * A November night of 2026 in the Alps: the sun 18 degrees below the horizon or more, a half moon 10 to 25
     * degrees up in the east (behind the patterns' bursts), stars and the Milky Way over the show to the north.
     */
    static final double NIGHT = night();

    private static double night() {
        double from = AstroTime.julianDay(2026, 11, 1, 16, 0, 0);
        Ephemeris e = new Ephemeris(EphemerisConfig.defaults());
        e.setObserver(Stage.ALPS);
        for (double jd = from; jd < from + 60; jd += 1.0 / 288) {
            e.update(jd);
            double az = e.moon().azimuth();
            if (e.sun().altitude() < -18 && e.moon().altitude() > 10 && e.moon().altitude() < 25 && az > 70
                && az < 115 && e.moonPhase().illuminatedFraction() > 0.3 && e.moonPhase().illuminatedFraction() < 0.8) {
                return jd;
            }
        }
        throw new IllegalStateException("No such night");
    }

    @Test
    void show() throws IOException, InterruptedException {
        Path dir = out("show");
        try (Stage s = new Stage(WIDTH, HEIGHT, false, 192, Stage.Look.FILM)) {
            night(s);
            long site = Village.build(s);
            FireworkShow show = FireworkShow.random(2026, 20, 1.1, 220)
                .and(FireworkShow.finale(2027, 20, 6.5, 70, 260));
            System.out.println(show + ", last launch at " + show.lastLaunch() + " s");
            s.scene.update();
            s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(show));
            int frames = 30 * 30;
            long start = System.nanoTime();
            // the first shells' lift unseen: the film starts as they near their apex
            look(s, 0);
            s.frames((int) (PRE_ROLL / STEP), STEP);
            for (int f = 0; f < frames; f++) {
                look(s, f / 30.0);
                s.frame(STEP);
                s.frame(STEP);
                write(s.shot(), dir, f, null);
            }
            s.device.waitIdle();
            System.out.printf("show: %d frames in %.1f s, %d shells, %d particles alive at the end, in %s%n", frames,
                (System.nanoTime() - start) / 1e9, s.fireworks.launched(), s.particles.alive(), dir.toAbsolutePath());
        }
    }

    @Test
    void patterns() throws IOException, InterruptedException {
        Path dir = out("patterns");
        try (Stage s = new Stage(WIDTH, HEIGHT, false, 128, Stage.Look.FILM)) {
            night(s);
            s.effects.wind(1.5f, 0, 0);
            long site = Village.build(s);
            s.scene.update();
            Pattern[] patterns = Pattern.values();
            Colorant[] colors = {Colorant.STRONTIUM_RED, Colorant.SODIUM_YELLOW, Colorant.CHARCOAL_GOLD,
                Colorant.BARIUM_GREEN, Colorant.CALCIUM_ORANGE, Colorant.COPPER_BLUE, Colorant.MAGNESIUM_WHITE,
                Colorant.TITANIUM_SILVER};
            Colorant[] seconds = {Colorant.BARIUM_GREEN, null, null, null, null, Colorant.PURPLE, null, null};
            int written = 0;
            for (int i = 0; i < patterns.length; i++) {
                Shell shell = new Shell(patterns[i], colors[i], seconds[i], 5);
                ShellDesign d = s.fireworks.shells().design(shell);
                double fuse = Ballistics.apexTime(d.launchSpeed(), d.shellDrag());
                double height = d.height();
                FireworkShow one = FireworkShow.builder(100 + i).cue(0, shell, 0, 0).build();
                s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(one));
                // from 170 m off, level with the burst a little below it; the camera waits through the lift
                double length = fuse + d.lightEnd() + 1.6;
                int steps = (int) Math.round(length / STEP);
                String label = shell.pattern().key() + ", " + shell.color().key()
                    + (shell.second() != null ? " and " + shell.second().key() : "") + ", 5 inch";
                for (int k = 0; k < steps; k++) {
                    double t = k * STEP;
                    s.look(-170, height * 0.8, Village.SITE_Z + 60, 0, height * 0.97, Village.SITE_Z, 40);
                    s.frame(STEP);
                    if (t > fuse - 1.2 && k % 2 == 1) {
                        write(s.shot(), dir, written++, label);
                    }
                }
                s.scene.components().remove(site, FireworkDisplay.TYPE);
                s.frames(60, STEP);
            }
            System.out.printf("patterns: %d frames in %s%n", written, dir.toAbsolutePath());
        }
    }

    /** Seconds of the show before the film starts: its first shells rising. */
    private static final double PRE_ROLL = 3.5;

    /** The show's camera {@code t} seconds into the film: on the square, turning slowly to the show and up with it. */
    private static void look(Stage s, double t) {
        double yaw = Math.toRadians(-8 + 10 * smooth(t / 30));
        double pitch = Math.toRadians(17 + 5 * smooth(t / 30));
        double x = 6 - 4 * smooth(t / 30);
        s.look(x, 1.7, 14, x + Math.sin(yaw) * 100, 1.7 + Math.tan(pitch) * 100, 14 - Math.cos(yaw) * 100, 70);
    }

    /**
     * The smoke close up: a 5-inch barium ring 120 m off, level with it, a few seconds after it its smoke spreading
     * and drifting, lit by the three shells bursting beyond it (their light from behind: forward scattering); 14 s.
     */
    @Test
    void smoke() throws IOException, InterruptedException {
        Path dir = out("smoke");
        try (Stage s = new Stage(WIDTH, HEIGHT, false, 128, Stage.Look.FILM)) {
            night(s);
            s.effects.wind(1.5f, 0, 0);
            long site = Village.build(s);
            s.scene.update();
            Shell ring = Shell.of(Pattern.RING, Colorant.BARIUM_GREEN, 5);
            ShellDesign d = s.fireworks.shells().design(ring);
            double fuse = Ballistics.apexTime(d.launchSpeed(), d.shellDrag());
            FireworkShow show = FireworkShow.builder(31).cue(0, ring, 0, 0)
                .cue(6, Shell.of(Pattern.CHRYSANTHEMUM, Colorant.SODIUM_YELLOW, 5), 40, -60)
                .cue(9, Shell.of(Pattern.PEONY, Colorant.STRONTIUM_RED, 5), -35, -50)
                .cue(11, Shell.of(Pattern.CHRYSANTHEMUM, Colorant.MAGNESIUM_WHITE, 5), -30, -80).build();
            s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(show));
            s.look(0, d.height() * 0.95, Village.SITE_Z + 150, 0, d.height(), Village.SITE_Z, 45);
            s.frames((int) ((fuse - 0.5) / STEP), STEP);
            int frames = 14 * 30;
            for (int f = 0; f < frames; f++) {
                s.frame(STEP);
                s.frame(STEP);
                write(s.shot(), dir, f, "after: smoke as a medium");
            }
            System.out.printf("smoke: %d frames in %s%n", frames, dir.toAbsolutePath());
        }
    }

    /**
     * The show's smoke on and off, 1920 x 1080, 20 s each from the same seed (the PNG frames of {@code smoke-on} and
     * {@code smoke-off}): a show over the village square, the camera low on the square so the bursts light the ground
     * and the houses, the smoke of the earlier shells drifting in the wind and lit by the later bursts; off as an
     * application without smoke ({@link FireworksSettings#SMOKE} and {@link FireworksSettings#BURST_SMOKE} 0).
     */
    @Test
    void smokeOnAndOff() throws IOException, InterruptedException {
        for (boolean smoke : new boolean[] {false, true}) {
            Path dir = out(smoke ? "smoke-on" : "smoke-off");
            try (Stage s = new Stage(1920, 1080, false, 256, Stage.Look.FILM)) {
                night(s);
                if (!smoke) {
                    s.settings.set(FireworksSettings.SMOKE, 0f);
                    s.settings.set(FireworksSettings.BURST_SMOKE, 0f);
                }
                long site = Village.build(s);
                FireworkShow show = FireworkShow.random(2026, 20, 1.1, 220);
                s.scene.update();
                s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(show));
                String label = smoke ? "after: smoke lit by the bursts, drifting" : "before: smoke off";
                long start = System.nanoTime();
                s.look(4, 3, 30, 4 - Math.sin(Math.toRadians(4)) * 100, 3 + Math.tan(Math.toRadians(20)) * 100,
                    30 - 100, 70);
                s.frames((int) (PRE_ROLL / STEP), STEP);
                int frames = 20 * 30;
                for (int f = 0; f < frames; f++) {
                    s.frame(STEP);
                    s.frame(STEP);
                    write(s.shot(), dir, f, label);
                }
                s.device.waitIdle();
                System.out.printf(Locale.ROOT, "smoke %s: %d frames in %.1f s, %d particles alive at the end, in %s%n",
                    smoke ? "on" : "off", frames, (System.nanoTime() - start) / 1e9, s.particles.alive(),
                    dir.toAbsolutePath());
            }
        }
    }

    private static double smooth(double x) {
        double c = Math.max(0, Math.min(1, x));
        return c * c * (3 - 2 * c);
    }

    private static void write(BufferedImage shot, Path dir, int f, String label) throws IOException {
        BufferedImage frame = shot;
        if (label != null) {
            frame = new BufferedImage(shot.getWidth(), shot.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = frame.createGraphics();
            g.drawImage(shot, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(g.getFont().deriveFont(26f));
            g.setColor(java.awt.Color.BLACK);
            g.drawString(label, 21, 41);
            g.setColor(java.awt.Color.WHITE);
            g.drawString(label, 20, 40);
            g.dispose();
        }
        ImageIO.write(frame, "png", dir.resolve(String.format(Locale.ROOT, "f%04d.png", f)).toFile());
    }
}
