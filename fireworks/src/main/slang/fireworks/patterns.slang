// The fireworks' particle modules (effects.md E24), which the build collects with the built-in ones into kernel
// variants of this library's (FireworkModules): where a shell's stars fly at its burst, by pattern and by their order of
// birth, and stars that strobe. GPU only: their Java mirror is Pattern.direction, which the tests hold them to.
module patterns;

import particles.particle;

/** A sphere of stars, spread evenly by the golden angle (peony, chrysanthemum, willow, glitter). */
public static const uint PATTERN_SPHERE = 0;
/** A ring of stars in a plane tilted `tilt` from the horizontal, about the azimuth `turn`. */
public static const uint PATTERN_RING = 1;
/** Arms on a cone `tilt` from the vertical: a palm's fronds. */
public static const uint PATTERN_PALM = 2;
/** A cross of `arms` arms about the vertical, turned at random per group of `arms` stars: a crossette's split. */
public static const uint PATTERN_CROSS = 3;

/** The unit direction of star `i` of `n` of a pattern (Pattern.direction in Java, to the last bit of its formula). */
public float3 patternDirection(uint kind, uint i, uint n, float tilt, float turn, float group)
{
    float m = float(max(n, 1u));
    if (kind == PATTERN_RING)
    {
        float phi = TAU * float(i) / m;
        // the ring's plane: its normal tilted from the vertical towards the azimuth turn
        float3 normal = float3(sin(tilt) * cos(turn), cos(tilt), sin(tilt) * sin(turn));
        float3 u = normalize(cross(normal, float3(-sin(turn), 0, cos(turn))));
        float3 v = cross(normal, u);
        return cos(phi) * u + sin(phi) * v;
    }
    if (kind == PATTERN_PALM)
    {
        float phi = TAU * float(i) / m + turn;
        return float3(sin(tilt) * cos(phi), cos(tilt), sin(tilt) * sin(phi));
    }
    if (kind == PATTERN_CROSS)
    {
        float phi = TAU * float(i) / m + TAU * group;
        return float3(cos(phi), 0, sin(phi));
    }
    // the golden-angle (Fibonacci) sphere: equal areas per star, turned about the vertical by turn
    float y = 1 - 2 * (float(i) + 0.5) / m;
    float r = sqrt(max(0.0, 1 - y * y));
    float phi = 2.3999632 * float(i) + turn;
    return float3(r * cos(phi), y, r * sin(phi));
}

/**
 * A burst's stars flying out by pattern: star i (its spawn index modulo `count`) of `count` along its direction of
 * the pattern `kind` (PATTERN_*), at `speed` m/s varied by `speedJitter` of itself, its direction jittered by up to
 * `jitter` radians; added to the velocity it has (a shell's, init.velocity). A cross splits each group of `arms`
 * stars (children of one parent are born one after another) into a cross turned at random per group.
 */
[ParticleModule("fireworks.pattern")]
public struct PatternInit
{
    public float arms = 4;
    public float count = 1;
    public float jitter = 0;
    public float kind = 0;
    public float speed = 1;
    public float speedJitter = 0;
    public float tilt = 0;
    public float turn = 0;

    public void init(inout ParticleInit s, inout Particle p)
    {
        uint k = uint(kind + 0.5);
        uint n = k == PATTERN_CROSS ? max(uint(arms + 0.5), 1u) : max(uint(count + 0.5), 1u);
        uint i = p.spawnIndex % n;
        float group = random(combine(s.emitter.seed, p.spawnIndex / n), s.salt + 8);
        float3 d = patternDirection(k, i, n, tilt, turn, group);
        if (jitter > 0)
        {
            // a small turn off the pattern, uniform in a cone of `jitter` radians
            float3 off = float3(random(p.seed, s.salt + 9), random(p.seed, s.salt + 10), random(p.seed, s.salt + 11))
                * 2 - 1;
            d = normalize(d + off * tan(min(jitter, 1.5)));
        }
        float v = speed * (1 + speedJitter * (2 * random(p.seed, s.salt + 12) - 1));
        s.direction = d;
        p.velocity += d * v;
    }
};

/**
 * Stars strobing: from `start` of their life, lit for `duty` of each of `rate` flashes a second (each star its own
 * phase), the rest of the time at `dark` of their brightness. After the attributes over life (it scales what they
 * left).
 */
[ParticleModule("fireworks.strobe")]
public struct StrobeOver
{
    public float dark = 0.03;
    public float duty = 0.35;
    public float rate = 12;
    public float start = 0;

    public void over(inout ParticleStep s, Particle p)
    {
        if (p.age >= start * p.lifetime)
        {
            float phase = frac(p.age * rate + random(p.seed, s.salt + 8));
            s.color *= phase > duty ? dark : 1.0;
            s.colored = true; // written lit too: the color is kept, so a dark frame would stay
        }
    }
};
