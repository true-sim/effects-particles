package com.truesim.effects.particles.fireworks;

import com.truesim.annotations.AllowAllocation;
import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * A fireworks show a node plays, as a component of the node (application-api.md D46; saved as
 * {@code truesim.fireworks.display}): the show starts when the component is added (or replaced), its mortars at
 * offsets in the node's frame, and stops firing when the component or the node goes (shells in the air burst as they
 * would). {@link FireworksSync} plays it.
 *
 * <pre>{@code
 * long square = scene.create();
 * scene.setTranslation(square, 0, 0, -250);
 * scene.components().set(square, FireworkDisplay.TYPE, FireworkDisplay.of(cache.get(showRef)));
 * }</pre>
 */
public final class FireworkDisplay {

    /** The fireworks display component type. */
    public static final ComponentType<FireworkDisplay> TYPE = ComponentType.of("truesim.fireworks.display",
        FireworkDisplay.class);

    private final FireworkShow show;
    private final double delay;

    private FireworkDisplay(FireworkShow show, double delay) {
        this.show = Objects.requireNonNull(show, "show");
        if (!(delay >= 0) || Double.isInfinite(delay)) {
            throw new IllegalArgumentException("Not a delay: " + delay);
        }
        this.delay = delay;
    }

    /** A display of {@code show}, starting as soon as it is added. */
    @AllowAllocation("components are made when a scene is built")
    public static FireworkDisplay of(FireworkShow show) {
        return new FireworkDisplay(show, 0);
    }

    /** A display of {@code show}, starting {@code delay} seconds after it is added. */
    @AllowAllocation("components are made when a scene is built")
    public static FireworkDisplay of(FireworkShow show, double delay) {
        return new FireworkDisplay(show, delay);
    }

    /** The show it plays. */
    public FireworkShow show() {
        return show;
    }

    /** Seconds from its being added to the show's start. */
    public double delay() {
        return delay;
    }

    @Override
    @AllowAllocation("diagnostics")
    public String toString() {
        return "fireworks display: " + show;
    }
}
