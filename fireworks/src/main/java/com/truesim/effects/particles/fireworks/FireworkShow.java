package com.truesim.effects.particles.fireworks;

import com.truesim.common.math.Maths;
import com.truesim.annotations.AllowAllocation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * A scripted fireworks show: a timeline of shells, each fired at its time from its mortar (an offset from the show's
 * node, in its frame) tilted from the vertical, and a seed from which every shell's stars, turns and tilts come, so
 * a show plays the same every time. Shows are data: made in code ({@link #builder}, {@link #random}, {@link #finale})
 * or loaded through the asset cache from {@code .fireworks} files ({@link FireworkAssets}), played by a
 * {@link FireworkDisplay} component on a node. Immutable.
 *
 * <p>The file format, a cue a line ({@code #} starts a comment):
 * <pre>
 * seed 7
 * # seconds  pattern        colour     second   inches  x    z    tilt  azimuth (degrees)
 * 0.0        peony          strontium  barium   4       0    0    0     0
 * 1.5        chrysanthemum  sodium     -        6       -20  5    8     90
 * </pre>
 */
public final class FireworkShow {

    /**
     * A shell fired: when (seconds after the show starts), what, from where (metres from the show's node, in its
     * frame: x right, z back) and how its mortar leans: {@code tilt} radians from the vertical towards {@code azimuth}
     * (radians from +x towards +z).
     */
    public record Cue(double time, Shell shell, double x, double z, double tilt, double azimuth) {

        public Cue {
            Objects.requireNonNull(shell, "shell");
            if (!(time >= 0) || Double.isInfinite(time)) {
                throw new IllegalArgumentException("Not a time: " + time);
            }
            if (!(tilt >= 0 && tilt < Math.PI / 2)) {
                throw new IllegalArgumentException("Not a mortar's tilt: " + tilt);
            }
        }
    }

    private final int seed;
    private final List<Cue> cues;

    private FireworkShow(int seed, List<Cue> cues) {
        this.seed = seed;
        List<Cue> sorted = new ArrayList<>(cues);
        sorted.sort(Comparator.comparingDouble(Cue::time)); // stable: cues at one time keep their order
        this.cues = List.copyOf(sorted);
    }

    /** Its seed. */
    public int seed() {
        return seed;
    }

    /** Its cues, by time. */
    public List<Cue> cues() {
        return cues;
    }

    /** Cue {@code i}. */
    public Cue cue(int i) {
        return cues.get(i);
    }

    /** How many cues it has. */
    public int size() {
        return cues.size();
    }

    /** When its last shell is fired, seconds. */
    public double lastLaunch() {
        return cues.isEmpty() ? 0 : cues.get(cues.size() - 1).time();
    }

    /** About how much memory it takes, bytes (the asset cache's measure). */
    public long bytes() {
        return 64 + 80L * cues.size();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FireworkShow s && s.seed == seed && s.cues.equals(cues);
    }

    @Override
    public int hashCode() {
        return 31 * seed + cues.hashCode();
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "fireworks show of " + cues.size() + " shells, seed " + seed;
    }

    /** A show of seed {@code seed}, cues added to it. */
    @AllowAllocation("shows are made as data")
    public static Builder builder(int seed) {
        return new Builder(seed);
    }

    /** Builds a show. */
    public static final class Builder {
        private final int seed;
        private final List<Cue> cues = new ArrayList<>();

        private Builder(int seed) {
            this.seed = seed;
        }

        /** Fires {@code shell} at {@code time} from ({@code x}, {@code z}), its mortar upright. */
        @AllowAllocation("shows are made as data")
        public Builder cue(double time, Shell shell, double x, double z) {
            return cue(new Cue(time, shell, x, z, 0, 0));
        }

        /** Adds {@code cue}. */
        @AllowAllocation("shows are made as data")
        public Builder cue(Cue cue) {
            cues.add(Objects.requireNonNull(cue, "cue"));
            return this;
        }

        @AllowAllocation("shows are made as data")
        public FireworkShow build() {
            return new FireworkShow(seed, cues);
        }
    }

    private static final Colorant[] STAR_COLORS = {Colorant.STRONTIUM_RED, Colorant.BARIUM_GREEN, Colorant.COPPER_BLUE,
        Colorant.SODIUM_YELLOW, Colorant.CALCIUM_ORANGE, Colorant.PURPLE, Colorant.MAGNESIUM_WHITE};
    private static final Pattern[] PATTERNS = Pattern.values();

    /**
     * A show of {@code seconds} drawn from {@code seed}: a shell every {@code interval} seconds on average (now and then
     * a pair or three at once), every pattern, colour and calibre from 3 to 7 inches, from mortars along a line
     * {@code width} metres wide, leaning up to 10 degrees.
     */
    @AllowAllocation("shows are made as data")
    public static FireworkShow random(int seed, double seconds, double interval, double width) {
        SplittableRandom random = new SplittableRandom(seed);
        Builder b = builder(seed);
        double t = 0;
        while (t < seconds) {
            int together = random.nextDouble() < 0.2 ? 2 + random.nextInt(2) : 1;
            Shell shell = randomShell(random, 3, 7);
            for (int k = 0; k < together; k++) {
                double x = (random.nextDouble() - 0.5) * width;
                b.cue(new Cue(t + 0.05 * k, shell, x, random.nextDouble(-4, 4), Maths.toRadians(random.nextDouble(10)),
                    random.nextDouble(2 * Math.PI)));
            }
            t += interval * (0.4 + 1.2 * random.nextDouble());
        }
        return b.build();
    }

    /**
     * A finale of {@code shells} shells over {@code seconds} drawn from {@code seed}, from {@code start}: the
     * mortars along a line {@code width} metres wide firing faster and faster, ending in a salvo of every mortar.
     */
    @AllowAllocation("shows are made as data")
    public static FireworkShow finale(int seed, double start, double seconds, int shells, double width) {
        SplittableRandom random = new SplittableRandom(seed);
        Builder b = builder(seed);
        int salvo = Math.max(1, shells / 6);
        int rising = shells - salvo;
        for (int i = 0; i < rising; i++) {
            // denser towards the end: times as the square root of an even spread
            double t = start + seconds * 0.85 * Math.sqrt((i + random.nextDouble()) / rising);
            b.cue(randomCue(random, t, width));
        }
        for (int i = 0; i < salvo; i++) {
            b.cue(randomCue(random, start + seconds * (0.9 + 0.1 * random.nextDouble()), width));
        }
        return b.build();
    }

    private static Cue randomCue(SplittableRandom random, double t, double width) {
        return new Cue(t, randomShell(random, 3, 6), (random.nextDouble() - 0.5) * width, random.nextDouble(-4, 4),
            Maths.toRadians(random.nextDouble(14)), random.nextDouble(2 * Math.PI));
    }

    private static Shell randomShell(SplittableRandom random, int smallest, int largest) {
        Pattern pattern = PATTERNS[random.nextInt(PATTERNS.length)];
        Colorant color = pattern == Pattern.WILLOW ? Colorant.CHARCOAL_GOLD
            : pattern == Pattern.STROBE || pattern == Pattern.CRACKLE
                ? (random.nextBoolean() ? Colorant.MAGNESIUM_WHITE : Colorant.TITANIUM_SILVER)
                : STAR_COLORS[random.nextInt(STAR_COLORS.length)];
        Colorant second = random.nextDouble() < 0.4 ? STAR_COLORS[random.nextInt(STAR_COLORS.length)] : null;
        return new Shell(pattern, color, second, smallest + random.nextInt(largest - smallest + 1));
    }

    /** The show and {@code other} together: their cues, this one's seed. */
    @AllowAllocation("shows are made as data")
    public FireworkShow and(FireworkShow other) {
        List<Cue> all = new ArrayList<>(cues);
        all.addAll(other.cues);
        return new FireworkShow(seed, all);
    }

    // ------------------------------------------------------------------------------------------ the file format

    /** The show as a {@code .fireworks} file's text. */
    @AllowAllocation("writing shows")
    public String text() {
        StringBuilder s = new StringBuilder("# true-sim fireworks show\nseed ").append(seed).append('\n')
            .append("# seconds  pattern  colour  second  inches  x  z  tilt  azimuth (degrees)\n");
        for (Cue c : cues) {
            Shell sh = c.shell();
            s.append(String.format(Locale.ROOT, "%.4f %s %s %s %s %.3f %.3f %.3f %.3f%n", c.time(), sh.pattern().key(),
                sh.color().key(), sh.second() == null ? "-" : sh.second().key(), number(sh.calibre()), c.x(), c.z(),
                Maths.toDegrees(c.tilt()), Maths.toDegrees(c.azimuth())));
        }
        return s.toString();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

    /** Reads a show from a {@code .fireworks} file's bytes; {@code what} names it in errors. */
    @AllowAllocation("reading shows")
    public static FireworkShow parse(byte[] bytes, String what) {
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
        Integer seed = null;
        List<Cue> cues = new ArrayList<>();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            int hash = line.indexOf('#');
            String[] w = (hash >= 0 ? line.substring(0, hash) : line).trim().split("\\s+");
            if (w.length == 1 && w[0].isEmpty()) {
                continue;
            }
            try {
                if (w[0].equals("seed") && w.length == 2) {
                    seed = Integer.parseInt(w[1]);
                } else if (w.length == 9) {
                    Shell shell = new Shell(Pattern.of(w[1]), Colorant.of(w[2]), w[3].equals("-") ? null
                        : Colorant.of(w[3]), Double.parseDouble(w[4]));
                    cues.add(new Cue(Double.parseDouble(w[0]), shell, Double.parseDouble(w[5]),
                        Double.parseDouble(w[6]), Maths.toRadians(Double.parseDouble(w[7])),
                        Maths.toRadians(Double.parseDouble(w[8]))));
                } else {
                    throw new IllegalArgumentException("a cue has 9 words, a seed line 2: " + w.length);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(what + ", line " + (n + 1) + ": " + e.getMessage(), e);
            }
        }
        if (seed == null) {
            throw new IllegalArgumentException(what + ": no seed line");
        }
        return new FireworkShow(seed, cues);
    }
}
