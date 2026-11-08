package com.truesim.effects.particles.fireworks;

import com.truesim.common.math.Maths;

/**
 * A shell's burst pattern: how its stars are laid out (a sphere by the golden angle, a ring, a palm's arms), how many
 * a calibre holds, how long they burn and how hard the air slows them, and what they do besides glowing (tails of
 * charcoal sparks, splitting, strobing, crackling). The figures are those of real shells, roughly: a burst some 20 m
 * across per inch of calibre (70 ft), stars burning 1.5 to 3 s, willows' charcoal 4 s and more, hanging in the air.
 *
 * <p>{@link #direction} is the Java mirror of the pattern module ({@code fireworks.pattern},
 * fireworks/patterns.slang): the same formula, in floats.
 */
public enum Pattern {

    /** A sphere of coloured stars without tails, the most common shell; with a pistil, an inner sphere. */
    PEONY(Layout.SPHERE, 9, 1.9, 0.9, 0.05, Tail.NONE),
    /** A peony whose stars leave trails of charcoal sparks. */
    CHRYSANTHEMUM(Layout.SPHERE, 8, 2.3, 0.9, 0.05, Tail.SPARKS),
    /** Long-burning charcoal stars in heavy air drag, falling slowly, their glitter hanging and drooping. */
    WILLOW(Layout.SPHERE, 6, 4.6, 1.5, 0.06, Tail.GLITTER),
    /** A ring of stars in a tilted plane. */
    RING(Layout.RING, 4, 1.9, 0.9, 0.015, Tail.NONE),
    /** A few heavy comets on a cone, thick tails curving down: a palm tree. */
    PALM(Layout.PALM, 0, 2.6, 0.55, 0.05, Tail.COMET),
    /** Comets that split, each into a cross of four, a third of the way out. */
    CROSSETTE(Layout.SPHERE, 1.4, 0.75, 0.7, 0.08, Tail.SPARKS),
    /** Stars blinking on and off: strobe (barium nitrate and magnalium in real ones). */
    STROBE(Layout.SPHERE, 7, 2.8, 1.0, 0.07, Tail.NONE),
    /** Glitter stars bursting into crackling micro-stars as they burn out (bismuth trioxide in real ones). */
    CRACKLE(Layout.SPHERE, 7, 1.5, 0.9, 0.07, Tail.NONE);

    /** How the stars are laid out: the pattern module's kinds. */
    public enum Layout {
        /** The golden-angle sphere. */
        SPHERE,
        /** A ring in a tilted plane. */
        RING,
        /** Arms on a cone about the vertical. */
        PALM,
        /** A cross per group of arms. */
        CROSS
    }

    /** What stars leave behind them besides smoke. */
    public enum Tail {
        /** Nothing. */
        NONE,
        /** Short-lived charcoal sparks. */
        SPARKS,
        /** Long-lived glitter, hanging and drooping. */
        GLITTER,
        /** Thick comet tails. */
        COMET
    }

    private final Layout layout;
    private final double starsPerSquareInch;
    private final double lifetime;
    private final double drag;
    private final double jitter;
    private final Tail tail;

    Pattern(Layout layout, double starsPerSquareInch, double lifetime, double drag, double jitter, Tail tail) {
        this.layout = layout;
        this.starsPerSquareInch = starsPerSquareInch;
        this.lifetime = lifetime;
        this.drag = drag;
        this.jitter = jitter;
        this.tail = tail;
    }

    public Layout layout() {
        return layout;
    }

    /** Stars of a shell of {@code calibre} inches: per square inch, a palm 6 arms and one more per inch. */
    public int stars(double calibre) {
        if (this == PALM) {
            return (int) Math.round(5 + calibre);
        }
        return Math.max(4, (int) Math.round(starsPerSquareInch * calibre * calibre));
    }

    /** How long a star burns, seconds, on average (a crossette's until it splits). */
    public double lifetime() {
        return lifetime;
    }

    /** The air's drag on a star, per second. */
    public double drag() {
        return drag;
    }

    /** How far stars stray from the pattern, radians. */
    public double jitter() {
        return jitter;
    }

    public Tail tail() {
        return tail;
    }

    /** Its name in shows' files: lower case. */
    @com.truesim.annotations.AllowAllocation("writing shows")
    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The pattern named {@code key}, in any case. */
    @com.truesim.annotations.AllowAllocation("reading shows")
    public static Pattern of(String key) {
        for (Pattern p : values()) {
            if (p.name().equalsIgnoreCase(key)) {
                return p;
            }
        }
        throw new IllegalArgumentException("Not a pattern: " + key);
    }

    /**
     * The unit direction of star {@code i} of {@code n} laid out as {@code layout} says (the pattern module's
     * {@code patternDirection}, in floats): a ring's plane tilted {@code tilt} radians from the horizontal towards the
     * azimuth {@code turn}; a palm's arms {@code tilt} from the vertical, turned by {@code turn}; a cross turned by
     * {@code group} of a turn; the sphere turned by {@code turn} about the vertical. Into {@code out}.
     */
    public static void direction(Layout layout, int i, int n, float tilt, float turn, float group, float[] out) {
        float m = Math.max(n, 1);
        float tau = 6.2831855f;
        switch (layout) {
            case RING -> {
                float phi = tau * i / m;
                float nx = (float) (Maths.sin(tilt) * Maths.cos(turn));
                float ny = (float) Maths.cos(tilt);
                float nz = (float) (Maths.sin(tilt) * Maths.sin(turn));
                // u = normalize(cross(normal, (-sin turn, 0, cos turn))), v = cross(normal, u)
                float ax = (float) -Maths.sin(turn);
                float az = (float) Maths.cos(turn);
                float ux = ny * az;
                float uy = nz * ax - nx * az;
                float uz = -ny * ax;
                float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                ux /= ul;
                uy /= ul;
                uz /= ul;
                float vx = ny * uz - nz * uy;
                float vy = nz * ux - nx * uz;
                float vz = nx * uy - ny * ux;
                float c = (float) Maths.cos(phi);
                float s = (float) Maths.sin(phi);
                out[0] = c * ux + s * vx;
                out[1] = c * uy + s * vy;
                out[2] = c * uz + s * vz;
            }
            case PALM -> {
                float phi = tau * i / m + turn;
                out[0] = (float) (Maths.sin(tilt) * Maths.cos(phi));
                out[1] = (float) Maths.cos(tilt);
                out[2] = (float) (Maths.sin(tilt) * Maths.sin(phi));
            }
            case CROSS -> {
                float phi = tau * i / m + tau * group;
                out[0] = (float) Maths.cos(phi);
                out[1] = 0;
                out[2] = (float) Maths.sin(phi);
            }
            default -> {
                float y = 1 - 2 * (i + 0.5f) / m;
                float r = (float) Math.sqrt(Math.max(0, 1 - y * y));
                float phi = 2.3999632f * i + turn;
                out[0] = (float) (r * Maths.cos(phi));
                out[1] = y;
                out[2] = (float) (r * Maths.sin(phi));
            }
        }
    }
}
