package com.truesim.effects.particles.fireworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.particles.layout.ParticleLayout;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.effects.Effect;
import com.truesim.scene.effects.EffectData;
import com.truesim.scene.effects.Spawn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Fireworks' smoke drawn as a medium, on the GPU, validated: a puff lets through e^-τ of what is behind it, τ its
 * optical depth as the smoke model has it at its age; lit by a light it scatters ω (1 - e^-τ) E p(θ) e^(-τ/2) towards
 * the camera, so a light behind it (forward scattering) lights it ((1 + g) / (1 - g))³ times as much as the same
 * light in front of it; and a palm's arms reach out most of their shell's radius.
 */
class SmokeGpuTest {

    private static final double DT = 1 / 60.0;
    private static final int SIZE = 128;
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

    /** A puff of the fireworks' smoke of {@code grams} and deviation {@code sigma0} at first, at (x, y, z). */
    private Effect puff(Smoke smoke, double sigma0, double grams, double x, double y, double z) {
        EffectData data = EffectData.builder().emitter("puff", e -> FireworkEffects.smoke(e, smoke, 1, sigma0, grams)
            .spawn(Spawn.burst(0, 1))).build(FireworkModules.MODULES.registry());
        return stage.effects.play(data, x, y, z);
    }

    /** The luminance of the last frame's pixel ({@code x}, {@code y}). */
    private double luminance(int x, int y) {
        float[] c = stage.color(x, y);
        return Spectra.luminance(c[0], c[1], c[2]);
    }

    /** The pixel a point at ({@code x}, 0, -20) projects to, the camera at the origin looking down -z, 60 degrees. */
    private static int column(double x) {
        return (int) Math.round((x / (20 * Math.tan(Math.toRadians(30))) * 0.5 + 0.5) * SIZE - 0.5);
    }

    @Test
    void aPuffLetsThroughWhatItsDepthSaysAndScattersLightForwards() {
        stage = new Stage(SIZE, SIZE, true, 16, Stage.Look.LINEAR);
        Stage s = stage;
        s.look(0, 0, 0, 0, 0, -1, 60);
        // still air: the puffs keep their depth while measured (their spreading is SmokeTest's, on its curves)
        s.settings.set(FireworksSettings.SMOKE_DIFFUSIVITY, 0f);
        Smoke smoke = Smoke.of(s.settings);
        // a glowing wall (1 cd/m²) 20 m behind the left puff (where the view ray through it meets 40 m out); the others
        // before the black sky
        int wall = s.material(0, 0, 0, 1, 1, 1, 1);
        s.place(s.box, wall, -12, 0, -40, 0, 0, 3.5f, 3.5f, 0.1f);
        double sigma0 = 0.8;
        double grams = 0.6; // a depth of about 0.45 at first
        Effect behind = puff(smoke, sigma0, grams, -6, 0, -20);
        Effect dark = puff(smoke, sigma0, grams, 0, 0, -20);
        Effect lit = puff(smoke, sigma0, grams, 6, 0, -20);
        s.frames(4, DT);
        int first = s.particles.first(behind.id(), 0);
        double age = s.particle(first, 0, ParticleLayout.AGE);
        double tau = smoke.centralDepth(grams, smoke.sigma(sigma0, age));
        assertThat(age).isPositive();
        assertThat((double) s.particle(first, 0, ParticleLayout.COLOR + 12)).as("its alpha: the depth at its centre")
            .isCloseTo(tau, within(0.03 * tau));
        int y = SIZE / 2;
        double through = luminance(column(-6), y) - luminance(column(0), y); // the sky's scattered light the same
        System.out.printf("puff of depth %.3f: lets through %.4f of the wall (e^-tau %.4f)%n", tau, through,
            Math.exp(-tau));
        assertThat(through).as("e^-tau of the wall's 1 cd/m² (half resolution softens the peak)")
            .isCloseTo(Math.exp(-tau), within(0.04));

        // A light 10 m behind the right puff, then 10 m before it: forward and back scattering
        double unlit = luminance(column(6), y);
        long light = s.scene.create();
        s.scene.setTranslation(light, 6, 0, -30);
        s.scene.components().set(light, LightSource.TYPE, LightSource.point(1, 1, 1, 400));
        s.frames(2, DT);
        int f = s.particles.first(lit.id(), 0);
        double tauForward = s.particle(f, 0, ParticleLayout.COLOR + 12);
        double litForward = luminance(column(6), y) - unlit;
        s.scene.setTranslation(light, 6, 0, -10);
        s.frames(2, DT);
        double tauBack = s.particle(f, 0, ParticleLayout.COLOR + 12);
        double litBack = luminance(column(6), y) - unlit;
        // the scattering angle's cosine: the light's way (along -z, or +z) against the way to the camera at the origin
        double toCamera = 20 / Math.hypot(6, 20);
        double expectedForward = smoke.scattered(tauForward, 400 / 100.0, toCamera);
        double expectedBack = smoke.scattered(tauBack, 400 / 100.0, -toCamera);
        System.out.printf("lit from behind %.5f (expected %.5f), from before %.5f (expected %.5f)%n", litForward,
            expectedForward, litBack, expectedBack);
        assertThat(litForward).as("forward scattering").isCloseTo(expectedForward, within(0.06 * expectedForward));
        assertThat(litBack).as("back scattering").isCloseTo(expectedBack, within(0.1 * expectedBack));
        assertThat(litForward / litBack).as("forwards: some 40 times as bright at these angles").isGreaterThan(30);
        assertThat(dark.alive()).isTrue();
    }

    @Test
    void aPalmsArmsReachOutMostOfItsShellsRadius() {
        stage = new Stage(64, 64, true, 16, Stage.Look.LINEAR);
        Stage s = stage;
        Shell palm = Shell.of(Pattern.PALM, Colorant.CALCIUM_ORANGE, 5);
        long site = s.scene.create();
        s.scene.update();
        s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(FireworkShow.builder(4)
            .cue(0, palm, 0, 0).build()));
        ShellDesign d = s.fireworks.shells().design(palm);
        while (s.fireworks.bursts() == 0) {
            s.frames(1, DT);
        }
        double burst = s.fireworks.time();
        s.frames((int) (0.8 * d.lifetime() / DT), DT); // every arm still burning
        long id = s.fireworks.lastBurst();
        int first = s.particles.first(id, 0);
        double[] centre = new double[2];
        int n = d.stars();
        for (int i = 0; i < n; i++) {
            centre[0] += s.particle(first, i, ParticleLayout.POSITION) / n;
            centre[1] += s.particle(first, i, ParticleLayout.POSITION + 8) / n;
        }
        double reach = 0;
        for (int i = 0; i < n; i++) {
            reach += Math.hypot(s.particle(first, i, ParticleLayout.POSITION) - centre[0],
                s.particle(first, i, ParticleLayout.POSITION + 8) - centre[1]) / n;
        }
        double t = s.fireworks.time() - burst;
        double flown = Ballistics.position(0, 0, d.starSpeed(), d.drag(), 0, t);
        System.out.printf("palm: arms out %.1f m at %.2f s, %.1f m flown, radius %.0f m%n", reach, t, flown,
            d.radius());
        assertThat(reach / flown).as("out at 57 to 77 degrees from the vertical").isBetween(0.8, 1.0);
        assertThat(reach).as("most of the shell's radius").isGreaterThan(0.7 * d.radius());
    }
}
