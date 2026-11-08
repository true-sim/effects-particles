package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;

/**
 * The sounds fireworks make, for audio, as effects-weather's lightning events are: a ring of the latest
 * {@link #capacity()} events as packed arrays, each with a sequential id, added as they happen (a shell's launch, its
 * burst, its crackles' pops). Each has its time (the {@link FireworksSync}'s clock, seconds), where it is (world
 * metres), how loud (the sound pressure level at 1 m, dB), the shell's seed and its calibre. A listener hears it
 * {@link #arrival late}: the distance over the speed of sound. Consumers keep the last id they handled and read what
 * came after ({@link #forEachSince}), or ask what reaches a listener in a span of time ({@link #forEachArriving});
 * one that falls more than the capacity behind misses the oldest. Adding and reading allocate nothing.
 */
public final class FireworkSounds {

    /** What made a sound. */
    public enum Kind {
        /** A shell leaving its mortar: the lift charge's thump. */
        LAUNCH,
        /** A shell bursting: the bursting charge's bang. */
        BURST,
        /** A crackle's micro-stars popping: one event for a volley of them. */
        CRACKLE;

        private static final Kind[] ALL = values();

        static Kind of(int ordinal) {
            return ALL[ordinal];
        }
    }

    /** Receives events. */
    @FunctionalInterface
    public interface Visitor {

        /** The event of {@code id} is in {@code slot} of {@code sounds}. */
        void event(FireworkSounds sounds, int slot, long id);
    }

    private final int capacity;
    private final double[] time;
    private final byte[] kind;
    private final double[] position;
    private final float[] level;
    private final int[] seed;
    private final float[] calibre;
    private long next;

    /** A ring of {@code capacity} events. */
    @AllowAllocation("made once")
    public FireworkSounds(int capacity) {
        if (capacity < 1 || capacity > 1 << 20) {
            throw new IllegalArgumentException("Not a capacity: " + capacity);
        }
        this.capacity = capacity;
        time = new double[capacity];
        kind = new byte[capacity];
        position = new double[3 * capacity];
        level = new float[capacity];
        seed = new int[capacity];
        calibre = new float[capacity];
    }

    public int capacity() {
        return capacity;
    }

    /** The id the next event gets; the number of events ever added. */
    public long nextId() {
        return next;
    }

    /** Adds a sound of {@code what} made at {@code t} at (x, y, z), {@code db} loud at 1 m; returns its id. */
    public long add(double t, Kind what, double x, double y, double z, float db, int shellSeed, float inches) {
        int s = (int) (next % capacity);
        time[s] = t;
        kind[s] = (byte) what.ordinal();
        position[3 * s] = x;
        position[3 * s + 1] = y;
        position[3 * s + 2] = z;
        level[s] = db;
        seed[s] = shellSeed;
        calibre[s] = inches;
        return next++;
    }

    /** Visits the events after {@code lastSeenId} (−1 for all retained), oldest first; returns the newest id or −1. */
    public long forEachSince(long lastSeenId, Visitor visitor) {
        long first = Math.max(lastSeenId + 1, next - capacity);
        for (long id = Math.max(first, 0); id < next; id++) {
            visitor.event(this, (int) (id % capacity), id);
        }
        return next - 1;
    }

    /**
     * Visits the retained events whose sound reaches the listener at (x, y, z) after {@code from} and up to
     * {@code to} (the clock's seconds), at {@code speedOfSound} m/s, oldest made first.
     */
    public void forEachArriving(double x, double y, double z, double from, double to, double speedOfSound,
        Visitor visitor) {
        for (long id = Math.max(next - capacity, 0); id < next; id++) {
            int s = (int) (id % capacity);
            double heard = arrival(s, x, y, z, speedOfSound);
            if (heard > from && heard <= to) {
                visitor.event(this, s, id);
            }
        }
    }

    /** When the sound in {@code slot} reaches (x, y, z): its time and the distance over {@code speedOfSound}. */
    public double arrival(int slot, double x, double y, double z, double speedOfSound) {
        return time[slot] + distance(slot, x, y, z) / speedOfSound;
    }

    /** The distance from the sound in {@code slot} to (x, y, z), metres. */
    public double distance(int slot, double x, double y, double z) {
        double dx = position[3 * slot] - x;
        double dy = position[3 * slot + 1] - y;
        double dz = position[3 * slot + 2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** When it was made, the clock's seconds. */
    public double time(int slot) {
        return time[slot];
    }

    public Kind kind(int slot) {
        return Kind.of(kind[slot]);
    }

    /** Its position's coordinate {@code axis} (0 x, 1 y, 2 z), metres. */
    public double position(int slot, int axis) {
        return position[3 * slot + axis];
    }

    /** Its sound pressure level at 1 m, dB. */
    public float level(int slot) {
        return level[slot];
    }

    /** Its shell's seed: the same shell's sounds share it (a sound bank picks its sample by it). */
    public int seed(int slot) {
        return seed[slot];
    }

    /** Its shell's calibre, inches. */
    public float calibre(int slot) {
        return calibre[slot];
    }
}
