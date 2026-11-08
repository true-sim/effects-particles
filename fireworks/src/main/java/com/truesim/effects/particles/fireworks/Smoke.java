package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.effects.ColorGradient;
import com.truesim.scene.effects.FloatCurve;

/**
 * Fireworks' smoke as a participating medium: what burning black powder and stars leave is an aerosol of potassium
 * salts (carbonate, sulphate, chloride) with some soot, submicron to a micron across, so it scatters much more than it
 * absorbs, mostly forwards. Each puff is a Gaussian of mass m (grams) whose deviation grows by turbulent diffusion,
 * σ² = σ0² + 2 K t, so its density falls as it spreads and its mass stays: the optical depth through its centre is
 * κ m / (2 π σ²) for the mass extinction coefficient κ (m²/g), the column over its whole area κ m. It scatters ω of
 * what it removes (the single-scattering albedo) by a Henyey-Greenstein phase function of asymmetry g, and the light
 * reaching its centre passes through half of it. The particles draw puffs so (the renderer setting {@code medium}),
 * three deviations across, lit per vertex by the lights about them, their alpha their optical depth at the centre
 * over life ({@link #depthOverLife}).
 *
 * <p>The figures (settings): κ about 3 m²/g, as obscurant aerosols of that size measure in the visible; ω 0.9; g 0.6;
 * K about 0.5 m²/s for puffs of metres in light wind; a star leaves about a gram of smoke a second, a shell's bursting
 * charge about 1.8 g per square inch of calibre (some 4.5 g of black powder per square inch, of which about 40 per
 * cent ends as solids).
 */
public final class Smoke {

    /** A puff over life is fitted at this many keys, spaced quadratically: closest early, where it changes most. */
    static final int KEYS = 24;
    /** The share of its life over which a puff fades out at its end (whatever is left). */
    static final float FADE = 0.12f;

    private final double extinction;
    private final double albedo;
    private final double phase;
    private final double diffusivity;
    private final double time;

    private Smoke(EngineSettings settings) {
        extinction = settings.get(FireworksSettings.SMOKE_EXTINCTION);
        albedo = settings.get(FireworksSettings.SMOKE_ALBEDO);
        phase = settings.get(FireworksSettings.SMOKE_PHASE);
        diffusivity = settings.get(FireworksSettings.SMOKE_DIFFUSIVITY);
        time = settings.get(FireworksSettings.SMOKE_TIME);
    }

    /** The smoke under {@code settings}. */
    @AllowAllocation("made when shells are designed")
    public static Smoke of(EngineSettings settings) {
        return new Smoke(settings);
    }

    /** The mass extinction coefficient, m²/g. */
    public double extinction() {
        return extinction;
    }

    /** The single-scattering albedo. */
    public double albedo() {
        return albedo;
    }

    /** The phase function's asymmetry. */
    public double phase() {
        return phase;
    }

    /** The turbulent diffusivity puffs spread by, m²/s. */
    public double diffusivity() {
        return diffusivity;
    }

    /** How long a puff lives, seconds. */
    public double time() {
        return time;
    }

    /** A puff's deviation {@code t} seconds after it began at {@code sigma0} metres: √(σ0² + 2 K t). */
    public double sigma(double sigma0, double t) {
        return Math.sqrt(sigma0 * sigma0 + 2 * diffusivity * Math.max(t, 0));
    }

    /** The optical depth through the centre of a puff of {@code grams} and deviation {@code sigma}: κ m / (2 π σ²). */
    public double centralDepth(double grams, double sigma) {
        return extinction * grams / (2 * Math.PI * sigma * sigma);
    }

    /** The optical depth at {@code b} metres off the centre of that puff: the centre's times e^(-b² / 2σ²). */
    public double depth(double grams, double sigma, double b) {
        return centralDepth(grams, sigma) * Maths.exp(-b * b / (2 * sigma * sigma));
    }

    /**
     * The radiance a thin puff of central depth {@code tau} scatters at its centre towards a viewer from a light giving
     * {@code irradiance} there at the cosine {@code cos} of the scattering angle (1: the light right behind it):
     * ω (1 - e^-τ) E p(cos) e^(-τ/2).
     */
    public double scattered(double tau, double irradiance, double cos) {
        return albedo * -Maths.expm1(-tau) * irradiance * henyeyGreenstein(phase, cos) * Maths.exp(-0.5 * tau);
    }

    /** The Henyey-Greenstein phase function of asymmetry {@code g} at the cosine {@code cos}, per steradian. */
    public static double henyeyGreenstein(double g, double cos) {
        double d = 1 + g * g - 2 * g * cos;
        return (1 - g * g) / (4 * Math.PI * d * Math.sqrt(d));
    }

    private static float key(int i) {
        float x = i / (KEYS - 1f);
        return x * x;
    }

    /** A puff's size (three deviations either way) over its life, times its size at birth: σ(t) / σ0. */
    @AllowAllocation("made when shells are designed")
    FloatCurve sizeOverLife(double sigma0) {
        FloatCurve.Builder b = FloatCurve.builder();
        for (int i = 0; i < KEYS; i++) {
            float u = key(i);
            b.linear(u, (float) (sigma(sigma0, u * time) / sigma0));
        }
        return b.build();
    }

    /**
     * A puff's optical depth over its life, times its depth at birth, as a gradient's alpha (its colour, the albedo,
     * kept): σ0² / σ(t)², faded out over its last {@link #FADE} of its life.
     */
    @AllowAllocation("made when shells are designed")
    ColorGradient depthOverLife(double sigma0) {
        ColorGradient.Builder b = ColorGradient.builder();
        for (int i = 0; i < KEYS; i++) {
            float u = key(i);
            double s = sigma(sigma0, u * time) / sigma0;
            float fade = u > 1 - FADE ? (1 - u) / FADE : 1;
            b.linear(u, 1, 1, 1, (float) (fade / (s * s)));
        }
        return b.build();
    }
}
