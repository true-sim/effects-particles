package com.truesim.effects.particles.fireworks;

import java.util.Objects;

/**
 * An aerial shell: its burst {@link Pattern}, the {@link Colorant} of its stars, an optional second colorant (a
 * peony's or a chrysanthemum's pistil, an inner sphere of stars; the colour of a crossette's split, a strobe's and a
 * crackle's micro-stars; null: none, or the stars' own), and its calibre in inches (3 to 12 for display shells),
 * which sets how high it bursts, how wide and how many stars it holds. Immutable, compared by value.
 *
 * @param pattern how its stars are laid out and behave
 * @param color what colours its stars
 * @param second its pistil's or its split's colour; null for none
 * @param calibre its diameter, inches
 */
public record Shell(Pattern pattern, Colorant color, Colorant second, double calibre) {

    /** The smallest and largest calibres, inches. */
    public static final double MIN_CALIBRE = 1;
    public static final double MAX_CALIBRE = 24;

    public Shell {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(color, "color");
        if (!(calibre >= MIN_CALIBRE && calibre <= MAX_CALIBRE)) {
            throw new IllegalArgumentException("Not a calibre: " + calibre + " inches");
        }
    }

    /** A shell of {@code calibre} inches of {@code pattern} in {@code color}, nothing else. */
    public static Shell of(Pattern pattern, Colorant color, double calibre) {
        return new Shell(pattern, color, null, calibre);
    }

    /** The colorant of its second stars: its own {@link #second()}, else its stars'. */
    public Colorant secondOrColor() {
        return second != null ? second : color;
    }

    /** Whether it has a pistil: a peony or chrysanthemum with a second colorant. */
    public boolean pistil() {
        return second != null && (pattern == Pattern.PEONY || pattern == Pattern.CHRYSANTHEMUM);
    }

    /** Its stars. */
    public int stars() {
        return pattern.stars(calibre);
    }
}
