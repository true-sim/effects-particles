package com.truesim.effects.particles.fireworks;

import com.truesim.common.math.Maths;

/**
 * Colours from spectra: an emission spectrum (lines of the molecules a star burns, and a hot body's continuum)
 * integrated against the CIE 1931 2-degree colour matching functions, in Wyman, Sloan and Shirley's multi-lobe fit
 * ("Simple Analytic Approximations to the CIE XYZ Color Matching Functions", JCGT 2013; within a few percent of the
 * tables), into linear Rec. 709 (sRGB's primaries, D65), normalised to a luminance of 1 and brought into the gamut by
 * mixing in white of the same luminance (a saturated copper blue or sodium yellow lies outside it).
 */
public final class Spectra {

    /** The second radiation constant, metre kelvin. */
    private static final double C2 = 1.438776877e-2;
    /** The wavelengths integrated over, nanometres, and the step. */
    private static final int FIRST = 380;
    private static final int LAST = 780;

    private Spectra() {
    }

    private static double lobe(double nm, double mean, double below, double above) {
        double t = (nm - mean) / (nm < mean ? below : above);
        return Maths.exp(-0.5 * t * t);
    }

    /** The CIE 1931 x-bar at {@code nm} nanometres (Wyman et al.'s fit). */
    public static double xBar(double nm) {
        return 1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7)
            - 0.065 * lobe(nm, 501.1, 20.4, 26.2);
    }

    /** The CIE 1931 y-bar (the photopic luminous efficiency) at {@code nm} nanometres. */
    public static double yBar(double nm) {
        return 0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1);
    }

    /** The CIE 1931 z-bar at {@code nm} nanometres. */
    public static double zBar(double nm) {
        return 1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8);
    }

    /** Planck's spectral radiance of a black body at {@code kelvin}, at {@code nm}, up to a constant factor. */
    public static double planck(double nm, double kelvin) {
        double m = nm * 1e-9;
        return 1.0 / (Maths.pow(m * 1e6, 5) * Maths.expm1(C2 / (m * kelvin)));
    }

    /**
     * The linear Rec. 709 colour of a spectrum of emission lines (each Gaussian, {@code lines} as triples of centre
     * nanometres, standard deviation nanometres and weight) and a black body's continuum at {@code kelvin} of weight
     * {@code continuum} (the lines and the continuum each normalised to unit power over the visible range first),
     * with a luminance of 1 and in the gamut.
     */
    public static float[] color(double[] lines, double kelvin, double continuum) {
        double lineSum = 0;
        double bodySum = 0;
        for (int nm = FIRST; nm <= LAST; nm++) {
            lineSum += lineSpectrum(lines, nm);
            bodySum += kelvin > 0 ? planck(nm, kelvin) : 0;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        for (int nm = FIRST; nm <= LAST; nm++) {
            double power = (lineSum > 0 ? lineSpectrum(lines, nm) / lineSum : 0)
                + (bodySum > 0 ? continuum * planck(nm, kelvin) / bodySum : 0);
            x += power * xBar(nm);
            y += power * yBar(nm);
            z += power * zBar(nm);
        }
        return rec709(x / y, 1, z / y);
    }

    private static double lineSpectrum(double[] lines, double nm) {
        double sum = 0;
        for (int i = 0; i + 2 < lines.length; i += 3) {
            double t = (nm - lines[i]) / lines[i + 1];
            sum += lines[i + 2] * Maths.exp(-0.5 * t * t);
        }
        return sum;
    }

    /** CIE XYZ into linear Rec. 709 (D65), luminance {@code y} kept, mixed with white where out of the gamut. */
    public static float[] rec709(double x, double y, double z) {
        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        double least = Math.min(r, Math.min(g, b));
        if (least < 0) {
            // white of luminance y is (y, y, y): mixing keeps the luminance, the least channel brought to 0
            double t = -least / (y - least);
            r += t * (y - r);
            g += t * (y - g);
            b += t * (y - b);
        }
        return new float[] {(float) Math.max(r, 0), (float) Math.max(g, 0), (float) Math.max(b, 0)};
    }

    /** The luminance of a linear Rec. 709 colour. */
    public static double luminance(float r, float g, float b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
}
