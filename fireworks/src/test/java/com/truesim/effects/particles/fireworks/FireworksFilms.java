package com.truesim.effects.particles.fireworks;

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
 * (effects-post) and the display transform; {@code patterns}, a close look at each pattern in turn. Each frame is two
 * steps of 1/60 s. Run with {@code TRUESIM_SCREENSHOTS=true ./gradlew :effects-particles-fireworks:test --tests
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
     * an eye or a camera does, between 1/64 and 1/2.8 per cd/m² (stars' cores burn out to white, the lit village and
     * smoke stay visible); AgX.
     */
    static void night(Stage s) {
        Bloom.SLOT.choose(s.settings, Bloom.PHYSICAL);
        s.settings.set(Bloom.PHYSICAL.<Float>setting("intensity"), 0.05f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("low"), 0.9f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("high"), 0.995f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("minEv"), -6f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("maxEv"), -1.5f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("speedUp"), 0.8f);
        s.settings.set(AutoExposure.HISTOGRAM.<Float>setting("speedDown"), 3f);
        s.settings.set(DisplayTransform.CURVE, "agx");
        s.effects.wind(2.5f, 0, 0.8f);
    }

    @Test
    void show() throws IOException {
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
            for (int f = 0; f < frames; f++) {
                double t = f / 30.0;
                // the camera on the square, turning slowly from the church to the show and up with it
                double yaw = Math.toRadians(-8 + 10 * smooth(t / 30));
                double pitch = Math.toRadians(17 + 5 * smooth(t / 30));
                double x = 6 - 4 * smooth(t / 30);
                s.look(x, 1.7, 14, x + Math.sin(yaw) * 100, 1.7 + Math.tan(pitch) * 100, 14 - Math.cos(yaw) * 100,
                    70);
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
    void patterns() throws IOException {
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
