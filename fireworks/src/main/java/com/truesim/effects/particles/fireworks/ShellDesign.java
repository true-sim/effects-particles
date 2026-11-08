package com.truesim.effects.particles.fireworks;

import com.truesim.engine.settings.EngineSettings;

/**
 * A {@link Shell}'s figures under the fireworks' settings: how fast it leaves the mortar to burst at its height, how
 * fast its stars fly out to reach its radius as the air slows them, how long they burn and how bright, and the light its
 * burst gives over time, which a burst's light follows ({@link #light}). Every figure the effects of the shell are made
 * from; immutable.
 */
public final class ShellDesign {

    /** Stars' lifetimes vary by this share of their mean either way (uniformly). */
    public static final double LIFETIME_JITTER = 0.15;
    /** A star's brightness over its life ({@link #burn}): ignition's flare, burning, burning out. */
    static final float[] BURN_TIMES = {0, 0.05f, 0.72f, 1};
    static final float[] BURN_VALUES = {2.2f, 1, 0.85f, 0};
    /** The bursting charge's flash: black powder's, a hot body at 2,600 K, of luminance 1. */
    static final float[] FLASH_RGB = Spectra.color(new double[0], 2600, 1);
    /** A crossette's children burn this long, seconds. */
    static final double SPLIT_LIFETIME = 1.3;
    /** A crossette splits at this share of its radius. */
    static final double SPLIT_RADIUS = 0.45;

    private final Shell shell;
    private final int stars;
    private final double radius;
    private final double height;
    private final double shellDrag;
    private final double launchSpeed;
    private final double lifetime;
    private final double drag;
    private final double starSpeed;
    private final double childSpeed;
    private final double starCandela;
    private final double secondCandela;
    private final double flashCandela;
    private final double flashTime;
    private final double glow;

    private ShellDesign(Shell shell, EngineSettings settings) {
        this.shell = shell;
        Pattern p = shell.pattern();
        double cal = shell.calibre();
        stars = shell.stars();
        radius = settings.get(FireworksSettings.BURST_RADIUS) * cal;
        height = settings.get(FireworksSettings.BURST_HEIGHT) * cal;
        shellDrag = settings.get(FireworksSettings.SHELL_DRAG);
        launchSpeed = Ballistics.launchSpeed(height, shellDrag);
        lifetime = p.lifetime();
        drag = p.drag();
        if (p == Pattern.CROSSETTE) {
            starSpeed = Ballistics.burstSpeed(radius * SPLIT_RADIUS, drag, lifetime);
            childSpeed = Ballistics.burstSpeed(radius * (1 - SPLIT_RADIUS), drag, SPLIT_LIFETIME);
        } else {
            starSpeed = Ballistics.burstSpeed(radius, drag, lifetime);
            childSpeed = 0;
        }
        double scale = settings.get(FireworksSettings.STAR_INTENSITY);
        starCandela = shell.color().candela() * scale;
        secondCandela = shell.secondOrColor().candela() * scale;
        flashCandela = settings.get(FireworksSettings.FLASH_INTENSITY) * cal * cal;
        flashTime = settings.get(FireworksSettings.FLASH_TIME);
        glow = settings.get(FireworksSettings.GLOW);
    }

    /** {@code shell}'s figures under {@code settings}. */
    @com.truesim.annotations.AllowAllocation("a design per shell type and settings")
    public static ShellDesign of(Shell shell, EngineSettings settings) {
        return new ShellDesign(shell, settings);
    }

    public Shell shell() {
        return shell;
    }

    /** Its stars. */
    public int stars() {
        return stars;
    }

    /** Its pistil's stars: a third of its own. */
    public int pistilStars() {
        return shell.pistil() ? Math.max(4, stars / 3) : 0;
    }

    /** How far its stars fly, metres. */
    public double radius() {
        return radius;
    }

    /** How high it bursts above its mortar fired straight up, metres. */
    public double height() {
        return height;
    }

    /** The air's drag on the shell rising, per second. */
    public double shellDrag() {
        return shellDrag;
    }

    /** Its speed out of the mortar, m/s. */
    public double launchSpeed() {
        return launchSpeed;
    }

    /** How long its stars burn on average, seconds (a crossette's until they split). */
    public double lifetime() {
        return lifetime;
    }

    /** The air's drag on its stars, per second. */
    public double drag() {
        return drag;
    }

    /** Its stars' speed out of the burst, m/s. */
    public double starSpeed() {
        return starSpeed;
    }

    /** A crossette's children's speed out of the split, m/s; 0 for others. */
    public double childSpeed() {
        return childSpeed;
    }

    /** A star's luminous intensity, candela. */
    public double starCandela() {
        return starCandela;
    }

    /** Its second stars' (pistil, split, ...) luminous intensity, candela. */
    public double secondCandela() {
        return secondCandela;
    }

    /** The bursting charge's flash at its start, candela. */
    public double flashCandela() {
        return flashCandela;
    }

    /** How long the flash lasts, seconds. */
    public double flashTime() {
        return flashTime;
    }

    /** How large a star is drawn, metres. */
    public double glow() {
        return glow;
    }

    /**
     * The luminance of a round sprite of {@code size} metres across giving {@code candela}: the round sprite's profile
     * (1 - r²) over its disc integrates to half its area, so L = I / (π s² / 8).
     */
    public static double spriteLuminance(double candela, double size) {
        return 8 * candela / (Math.PI * size * size);
    }

    /** A star's brightness at {@code u} of its life (0 to 1), times its burning brightness: the effects' gradient. */
    public static double burn(double u) {
        if (u < 0 || u >= 1) {
            return 0;
        }
        for (int i = 1; i < BURN_TIMES.length; i++) {
            if (u <= BURN_TIMES[i]) {
                double f = (u - BURN_TIMES[i - 1]) / (BURN_TIMES[i] - BURN_TIMES[i - 1]);
                return BURN_VALUES[i - 1] + f * (BURN_VALUES[i] - BURN_VALUES[i - 1]);
            }
        }
        return 0;
    }

    /**
     * The mean brightness of stars of mean lifetime {@code mean} at {@code t} seconds after their birth, their
     * lifetimes uniform within {@link #LIFETIME_JITTER} of the mean: 16 lifetimes, at the middle of 16 equal shares.
     */
    static double meanBurn(double t, double mean) {
        double sum = 0;
        for (int i = 0; i < 16; i++) {
            double life = mean * (1 + LIFETIME_JITTER * (2 * (i + 0.5) / 16 - 1));
            sum += burn(t / life);
        }
        return sum / 16;
    }

    /**
     * The burst's luminous intensity {@code t} seconds after it burst, candela: its stars burning (a crossette's
     * children after they split), its pistil, and the bursting charge's flash, fading linearly over its time.
     */
    public double light(double t) {
        return light(t, null);
    }

    /**
     * {@link #light(double)}, and the light's colour (luminance 1: the colorants' mixed by their shares) into
     * {@code rgb} unless null.
     */
    public double light(double t, float[] rgb) {
        if (t < 0) {
            return 0;
        }
        double first = stars * starCandela * meanBurn(t, lifetime);
        double second = pistilStars() * secondCandela * meanBurn(t, lifetime);
        if (shell.pattern() == Pattern.CROSSETTE) {
            // children are born as their parent dies: the parents' lifetimes, then their own
            double children = 0;
            for (int i = 0; i < 16; i++) {
                double life = lifetime * (1 + LIFETIME_JITTER * (2 * (i + 0.5) / 16 - 1));
                children += meanBurn(t - life, SPLIT_LIFETIME);
            }
            second += 4 * stars * secondCandela * children / 16;
        }
        double flash = flash(t);
        double sum = first + second + flash;
        if (rgb != null) {
            Colorant a = shell.color();
            Colorant b = shell.secondOrColor();
            for (int c = 0; c < 3; c++) {
                rgb[c] = sum > 0 ? (float) ((first * a.rgb(c) + second * b.rgb(c) + flash * FLASH_RGB[c]) / sum) : 1;
            }
        }
        return sum;
    }

    /** The bursting charge's flash {@code t} seconds after the burst, candela. */
    public double flash(double t) {
        return t >= 0 && t < flashTime ? flashCandela * (1 - t / flashTime) : 0;
    }

    /** When its light is out, seconds after the burst: its stars' and their children's longest lives. */
    public double lightEnd() {
        double end = lifetime * (1 + LIFETIME_JITTER);
        if (shell.pattern() == Pattern.CROSSETTE) {
            end += SPLIT_LIFETIME * (1 + LIFETIME_JITTER);
        }
        return Math.max(end, flashTime);
    }
}
