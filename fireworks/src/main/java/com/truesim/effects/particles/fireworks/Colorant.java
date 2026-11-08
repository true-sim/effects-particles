package com.truesim.effects.particles.fireworks;

/**
 * What colours a star: the emitter its composition makes in the flame, as an emission spectrum (the molecular bands
 * pyrotechnic literature gives: Shimizu, "Fireworks: The Art, Science and Technique", 1981; Kosanke and Kosanke,
 * "Pyrotechnic Spectroscopy") and a hot body's continuum, turned into a linear Rec. 709 colour of luminance 1
 * ({@link Spectra}); and a star's luminous intensity in candela as it burns, the "-ish" of a 1 to 2 cm star (blue stars
 * are notoriously the dimmest, sodium the brightest for its size). A star's colour times its intensity is what a
 * burst's light and its stars' brightness are made of.
 */
public enum Colorant {

    /** Strontium red: SrCl bands at 636, 648, 661 and 674 nm and SrOH at 606 nm. */
    STRONTIUM_RED(new double[] {606, 5, 0.18, 636, 4, 0.28, 648, 4, 0.14, 661, 5, 0.28, 674, 5, 0.12}, 2200, 0.04,
        420),
    /** Barium green: BaCl bands from 507 to 532 nm, a little BaOH at 487 nm. */
    BARIUM_GREEN(new double[] {487, 4, 0.05, 507, 3, 0.15, 513, 3, 0.25, 517, 3, 0.15, 524, 4, 0.25, 532, 4, 0.15},
        2200, 0.05, 460),
    /** Copper blue: CuCl bands from 420 to 452 nm and weak ones at 476 and 488 nm. */
    COPPER_BLUE(new double[] {420, 4, 0.14, 428, 4, 0.2, 435, 4, 0.24, 443, 4, 0.2, 452, 4, 0.14, 476, 4, 0.04, 488,
        4, 0.04}, 2000, 0.04, 140),
    /** Sodium yellow: the D lines at 589.0 and 589.6 nm, pressure-broadened, on a dim continuum. */
    SODIUM_YELLOW(new double[] {589.0, 3, 0.6, 589.6, 3, 0.4}, 2000, 0.08, 900),
    /** Calcium orange: CaCl at 593 and 618 nm, CaOH at 554 and 622 nm. */
    CALCIUM_ORANGE(new double[] {554, 6, 0.15, 593, 4, 0.3, 618, 4, 0.3, 622, 6, 0.25}, 2100, 0.05, 450),
    /** Purple: strontium red and copper blue in one star. */
    PURPLE(new double[] {636, 4, 0.12, 661, 5, 0.12, 674, 5, 0.06, 428, 4, 0.2, 435, 4, 0.24, 443, 4, 0.2, 452, 4,
        0.14}, 2000, 0.03, 220),
    /** Magnalium white: magnesium oxide glowing white hot. */
    MAGNESIUM_WHITE(new double[0], 4600, 1, 1200),
    /** Titanium silver: bright white-silver sparks (crackle, strobe, flitter). */
    TITANIUM_SILVER(new double[0], 3400, 1, 650),
    /** Charcoal gold: burning charcoal's glitter, willows and tails, a black body at 1,900 K. */
    CHARCOAL_GOLD(new double[0], 1900, 1, 110);

    private final float[] rgb;
    private final float candela;

    Colorant(double[] lines, double kelvin, double continuum, float candela) {
        this.rgb = Spectra.color(lines, kelvin, continuum);
        this.candela = candela;
    }

    /** Component {@code c} (0 red, 1 green, 2 blue) of its linear Rec. 709 colour of luminance 1. */
    public float rgb(int c) {
        return rgb[c];
    }

    /** A burning star's luminous intensity, candela, before {@link FireworksSettings#STAR_INTENSITY}'s scale. */
    public float candela() {
        return candela;
    }

    /** Its name in shows' files: lower case, its element first ({@code strontium}, {@code barium}, ...). */
    @com.truesim.annotations.AllowAllocation("writing shows")
    public String key() {
        return name().substring(0, name().indexOf('_') < 0 ? name().length() : name().indexOf('_'))
            .toLowerCase(java.util.Locale.ROOT);
    }

    /** The colorant of {@code key} ({@link #key()}, or the whole name in any case). */
    @com.truesim.annotations.AllowAllocation("reading shows")
    public static Colorant of(String key) {
        for (Colorant c : values()) {
            if (c.key().equalsIgnoreCase(key) || c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Not a colorant: " + key);
    }
}
