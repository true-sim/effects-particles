package com.truesim.effects.particles.fireworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.FloatCurve;
import org.junit.jupiter.api.Test;

/**
 * Fireworks' smoke as a medium, without a GPU: puffs spreading by diffusion with their mass kept and their depth
 * falling, the phase function forwards, the curves the particles take over a puff's life; and palms opening as wide
 * as their shells' radius.
 */
class SmokeTest {

    private final EngineSettings settings = new EngineSettings();
    private final Smoke smoke = Smoke.of(settings);

    @Test
    void aPuffSpreadsByDiffusionItsMassKeptAndItsDepthFalling() {
        double grams = 0.125;
        double sigma0 = 0.8;
        assertThat(smoke.sigma(sigma0, 0)).isEqualTo(sigma0);
        // sigma² grows by 2 K t
        assertThat(Math.pow(smoke.sigma(sigma0, 10), 2) - sigma0 * sigma0).isCloseTo(2 * 0.5 * 10, within(1e-9));
        for (double t : new double[] {0, 1, 5, 20}) {
            double s = smoke.sigma(sigma0, t);
            // the column over the puff's whole area is kappa m: its mass, whatever its spread
            double column = 0;
            double step = s / 50;
            for (double x = -6 * s; x <= 6 * s; x += step) {
                for (double y = -6 * s; y <= 6 * s; y += step) {
                    column += smoke.depth(grams, s, Math.hypot(x, y)) * step * step;
                }
            }
            assertThat(column).as("the column at %.0f s", t).isCloseTo(3 * grams, within(1e-3));
        }
        // the depth through the centre: kappa m / (2 pi sigma²), falling as sigma² grows
        assertThat(smoke.centralDepth(grams, sigma0)).isCloseTo(3 * 0.125 / (2 * Math.PI * 0.64), within(1e-12));
        assertThat(smoke.centralDepth(grams, smoke.sigma(sigma0, 20)))
            .isCloseTo(smoke.centralDepth(grams, sigma0) * 0.64 / (0.64 + 20), within(1e-12));
    }

    @Test
    void theParticlesCurvesFollowAPuffsSpreadingOverItsLife() {
        double sigma0 = 0.8;
        FloatCurve size = smoke.sizeOverLife(sigma0);
        ColorGradient depth = smoke.depthOverLife(sigma0);
        float[] rgba = new float[4];
        for (double t : new double[] {0, 0.25, 1, 3, 10, 24}) {
            float u = (float) (t / smoke.time());
            double s = smoke.sigma(sigma0, t) / sigma0;
            assertThat((double) size.evaluate(u)).as("size at %.2f s", t).isCloseTo(s, within(0.02 * s));
            depth.evaluate(u, rgba, 0);
            assertThat((double) rgba[3]).as("depth at %.2f s", t).isCloseTo(1 / (s * s), within(0.03 / (s * s)));
            assertThat(rgba[0]).as("the albedo kept").isEqualTo(1);
        }
        depth.evaluate(1, rgba, 0);
        assertThat(rgba[3]).as("faded out at its end").isZero();
    }

    @Test
    void smokeScattersForwardsByItsPhaseFunction() {
        double g = smoke.phase();
        assertThat(g).isEqualTo(0.6f, within(1e-6));
        // normalised over the sphere, its mean cosine g
        double sum = 0;
        double mean = 0;
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            double c = -1 + 2 * (i + 0.5) / n;
            double p = Smoke.henyeyGreenstein(g, c) * 2 * Math.PI * 2.0 / n;
            sum += p;
            mean += c * p;
        }
        assertThat(sum).isCloseTo(1, within(1e-4));
        assertThat(mean).isCloseTo(g, within(1e-3));
        // a light behind a puff: ((1 + g) / (1 - g))³ times what one in front of it gives
        double forward = smoke.scattered(0.2, 10, 1);
        double back = smoke.scattered(0.2, 10, -1);
        assertThat(forward / back).isCloseTo(Math.pow(1.6 / 0.4, 3), within(1e-3)); // g is a float setting
        // thin: the albedo times the depth, the irradiance and the phase, less what it shadows of itself
        assertThat(smoke.scattered(0.01, 10, 0)).isCloseTo(0.9 * 0.01 * 10 * Smoke.henyeyGreenstein(g, 0),
            within(2e-4));
    }

    @Test
    void palmsOpenAsWideAsTheirShellsRadius() {
        // a palm's arms leave 57 to 77 degrees from the vertical and fly the shell's radius: their reach out is most
        // of it, as real palms' fronds spread about as wide as a peony of the calibre
        ShellDesign palm = ShellDesign.of(Shell.of(Pattern.PALM, Colorant.CALCIUM_ORANGE, 5), settings);
        double reach = Ballistics.position(0, 0, palm.starSpeed(), palm.drag(), 0, palm.lifetime());
        assertThat(reach).isCloseTo(palm.radius(), within(1e-9));
        assertThat(Math.sin(ShellDesign.PALM_TILT) * reach).as("the narrowest palm's reach out")
            .isGreaterThan(0.8 * palm.radius());
        assertThat(ShellDesign.PALM_TILT + ShellDesign.PALM_TILT_SPREAD).isLessThan(Math.PI / 2);
        // its comets are heavy stars
        ShellDesign peony = ShellDesign.of(Shell.of(Pattern.PEONY, Colorant.CALCIUM_ORANGE, 5), settings);
        assertThat(palm.starCandela()).isEqualTo(3 * peony.starCandela());
    }
}
