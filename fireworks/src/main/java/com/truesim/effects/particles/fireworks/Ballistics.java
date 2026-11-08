package com.truesim.effects.particles.fireworks;

import com.truesim.common.math.Maths;

/**
 * Motion under gravity and linear drag through moving air, in closed form: dv/dt = g - k (v - w), as the particle
 * kernel integrates gravity and its wind force (a drag of {@code k} per second towards the world's wind {@code w})
 * exactly over each step. With a = g + k w the velocity is a / k + (v0 - a / k) e^(-k t), approaching the terminal
 * velocity a / k, and the position x0 + (a / k) t + (v0 - a / k) (1 - e^(-k t)) / k. Shells, stars and the centre of a
 * burst (its stars' mean: their velocities add up to the shell's, as the pattern's directions sum to nothing, and
 * the motion is linear in the velocity) all move so. Gravity is -{@link #G} along y.
 */
public final class Ballistics {

    /** Gravity, m/s², as the particle kernel's. */
    public static final double G = 9.81;

    private Ballistics() {
    }

    /** (1 - e^(-k t)) / k, and t at k = 0. */
    private static double fade(double k, double t) {
        return k * t < 1e-6 ? t * (1 - 0.5 * k * t) : -Maths.expm1(-k * t) / k;
    }

    /**
     * Component {@code axis} (0 x, 1 y up, 2 z) of the position after {@code t} seconds of something starting at
     * {@code x0} with velocity {@code v0}, drag {@code k} per second and the air moving at {@code wind} (m/s, this
     * axis').
     */
    public static double position(int axis, double x0, double v0, double k, double wind, double t) {
        double a = (axis == 1 ? -G : 0) + k * wind;
        if (k == 0) {
            return x0 + v0 * t + 0.5 * a * t * t;
        }
        double terminal = a / k;
        return x0 + terminal * t + (v0 - terminal) * fade(k, t);
    }

    /** Component {@code axis} of the velocity after {@code t} seconds, as {@link #position}. */
    public static double velocity(int axis, double v0, double k, double wind, double t) {
        double a = (axis == 1 ? -G : 0) + k * wind;
        if (k == 0) {
            return v0 + a * t;
        }
        double terminal = a / k;
        return terminal + (v0 - terminal) * Maths.exp(-k * t);
    }

    /** When something rising at {@code vy} m/s with drag {@code k} stops rising: ln(1 + k vy / g) / k. */
    public static double apexTime(double vy, double k) {
        if (vy <= 0) {
            return 0;
        }
        return k * vy < 1e-9 ? vy / G : Maths.log1p(k * vy / G) / k;
    }

    /**
     * When something rising at {@code vy} m/s with drag {@code k} through air rising at {@code windY} m/s stops
     * rising: the velocity's terminal T = (k windY - g) / k reached from vy as e^(-k t), zero at ln((vy - T) / -T) / k.
     */
    public static double apexTime(double vy, double k, double windY) {
        if (vy <= 0) {
            return 0;
        }
        double a = k * windY - G;
        if (a >= 0) {
            throw new IllegalArgumentException("Air rising at " + windY + " m/s carries it up for ever at drag " + k);
        }
        if (k * vy < 1e-9) {
            return -vy / a;
        }
        double terminal = a / k;
        return Maths.log((vy - terminal) / -terminal) / k;
    }

    /** How high something launched upwards at {@code vy} m/s with drag {@code k} rises: (vy - g t_apex) / k. */
    public static double apexHeight(double vy, double k) {
        if (vy <= 0) {
            return 0;
        }
        if (k * vy < 1e-9) {
            return vy * vy / (2 * G);
        }
        return (vy - G * apexTime(vy, k)) / k;
    }

    /** The upward speed that rises {@code height} metres with drag {@code k}: the inverse of {@link #apexHeight}. */
    public static double launchSpeed(double height, double k) {
        if (height <= 0) {
            return 0;
        }
        double v = Math.sqrt(2 * G * height); // without drag: a start from below the answer
        for (int i = 0; i < 50; i++) {
            // d(height)/dv = (1 - g / (g + k v)) / k = v / (g + k v)
            double error = apexHeight(v, k) - height;
            double slope = v / (G + k * v);
            double next = v - error / slope;
            if (Math.abs(next - v) < 1e-10 * v) {
                return next;
            }
            v = next;
        }
        return v;
    }

    /**
     * The speed out of a burst that carries a star {@code radius} metres in {@code seconds} against drag {@code k}
     * (gravity aside): radius k / (1 - e^(-k t)).
     */
    public static double burstSpeed(double radius, double k, double seconds) {
        return radius / fade(k, seconds);
    }
}
