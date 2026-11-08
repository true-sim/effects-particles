package com.truesim.effects.particles.fireworks;

import com.truesim.scene.core.LightSource;
import java.util.SplittableRandom;

/**
 * An Alpine village square at night for the films and the benchmark: a meadow, a cobbled square, houses round it with
 * pale walls, steep dark roofs and a few warm windows, a church tower, a fountain's basin, four street lamps (scene
 * light components, so the engine's light sync places them as it does the bursts'), and the show's site, a field
 * beyond the houses ({@link #SITE_Z} metres up the -z axis).
 */
final class Village {

    /** How far the mortars stand from the square, metres along -z. */
    static final double SITE_Z = -380;

    private Village() {
    }

    /** Builds the village on {@code stage}; returns the show's site node. */
    static long build(Stage stage) {
        int meadow = stage.material(0.10f, 0.13f, 0.08f, 0.95f, 0, 0, 0);
        int cobbles = stage.material(0.30f, 0.29f, 0.27f, 0.85f, 0, 0, 0);
        int wall = stage.material(0.74f, 0.70f, 0.62f, 0.9f, 0, 0, 0);
        int timber = stage.material(0.30f, 0.20f, 0.12f, 0.8f, 0, 0, 0);
        int roof = stage.material(0.34f, 0.26f, 0.22f, 0.7f, 0, 0, 0);
        // emissive and light intensities in the stage's scene units (pre-exposed as the fireworks' are)
        float u = stage.settings.get(FireworksSettings.LUMINANCE_SCALE);
        int window = stage.material(0.05f, 0.05f, 0.05f, 0.4f, 22 * u, 13 * u, 5 * u);
        int stone = stage.material(0.42f, 0.40f, 0.38f, 0.8f, 0, 0, 0);
        int bulb = stage.material(0.1f, 0.1f, 0.1f, 0.5f, 900 * u, 560 * u, 220 * u);
        int iron = stage.material(0.05f, 0.05f, 0.06f, 0.4f, 0, 0, 0);
        stage.place(stage.box, meadow, 0, -0.5, -200, 0, 0, 2000, 0.5f, 2000);
        stage.place(stage.box, cobbles, 0, 0.01, 0, 0, 0, 22, 0.02f, 22);
        // the fountain's basin
        stage.place(stage.box, stone, 0, 0.35, 0, 0.3, 0, 2.2f, 0.35f, 2.2f);
        SplittableRandom random = new SplittableRandom(1848);
        // houses round the square, gables facing it; the far side's lower so the sky shows over them
        double[][] houses = {{-30, -26, 0.15}, {-12, -30, -0.05}, {10, -31, 0.08}, {28, -25, -0.2}, {-34, -2, 1.57},
            {-33, 18, 1.5}, {34, 0, -1.6}, {33, 19, -1.5}, {-22, 36, 3.1}, {20, 37, 3.2}};
        for (double[] h : houses) {
            house(stage, random, h[0], h[1], h[2], wall, timber, roof, window);
        }
        // the church tower, behind the square's left, and its spire
        stage.place(stage.box, wall, -48, 12, -48, 0.2, 0, 4, 12, 4);
        stage.place(stage.box, roof, -48, 27.5, -48, 0.2 + Math.PI / 4, 0, 2.6f, 3.5f, 2.6f);
        stage.place(stage.box, window, -48, 20, -43.9, 0.2, 0, 0.6f, 1.2f, 0.05f);
        // four street lamps at the square's corners
        double[][] lamps = {{-15, -15}, {15, -15}, {-15, 15}, {15, 15}};
        for (double[] l : lamps) {
            stage.place(stage.box, iron, l[0], 2, l[1], 0, 0, 0.06f, 2, 0.06f);
            stage.place(stage.box, bulb, l[0], 4.1, l[1], 0, 0, 0.12f, 0.15f, 0.12f);
            long lamp = stage.scene.create();
            stage.scene.setTranslation(lamp, l[0], 4.0, l[1]);
            stage.scene.components().set(lamp, LightSource.TYPE, LightSource.point(1, 0.62f, 0.25f, 260 * u)
                .range(40));
        }
        // the field the mortars stand in, beyond the far houses, and a few trees' dark shapes along it
        for (int i = 0; i < 40; i++) {
            double x = random.nextDouble(-400, 400);
            double z = random.nextDouble(-260, -120);
            float s = (float) random.nextDouble(2, 5);
            stage.place(stage.rock, meadow, x, s, z, random.nextDouble(6), 0, s * 0.8f, s * 1.6f, s * 0.8f);
        }
        long site = stage.scene.create();
        stage.scene.setTranslation(site, 0, 0, SITE_Z);
        return site;
    }

    /** A house at (x, z) turned by yaw: walls, timbered upper floor, a steep roof of two slopes, lit windows. */
    private static void house(Stage stage, SplittableRandom random, double x, double z, double yaw, int wall,
        int timber, int roof, int window) {
        float w = (float) random.nextDouble(4.5, 6.5);
        float d = (float) random.nextDouble(5, 7);
        float h = (float) random.nextDouble(3.2, 4.4);
        stage.place(stage.box, wall, x, h, z, yaw, 0, w, h, d);
        stage.place(stage.box, timber, x, 2 * h + 0.15, z, yaw, 0, w + 0.2f, 0.15f, d + 0.2f);
        // the roof: two slabs leaning 45 degrees, meeting over the ridge along the house's depth
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        double off = w * 0.5;
        float slab = (float) (w * 0.75);
        for (int side = -1; side <= 1; side += 2) {
            double ox = side * off * c;
            double oz = -side * off * s;
            stage.place(stage.box, roof, x + ox, 2 * h + 0.3 + off, z + oz, yaw, -side * Math.PI / 4, slab, 0.18f,
                d + 0.6f);
        }
        // windows on the side facing +z of the house's frame: some lit
        for (int i = -1; i <= 1; i++) {
            for (int floor = 0; floor < 2; floor++) {
                if (random.nextDouble() < 0.45) {
                    double wx = i * w * 0.55;
                    double wy = h * (0.6 + 0.9 * floor);
                    stage.place(stage.box, window, x + wx * c + (d + 0.02) * s, wy, z - wx * s + (d + 0.02) * c, yaw,
                        0, 0.45f, 0.6f, 0.04f);
                }
            }
        }
    }
}
