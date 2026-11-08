package com.truesim.effects.particles.fireworks;

import com.truesim.engine.graph.GpuTimings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A dense finale's cost (TRUESIM_BENCHMARK=true): 120 shells of every pattern fired over 8 s from 240 m of mortars,
 * the last sixth at once, over the village at 1920 x 1080 with the films' whole look (night sky, clustered lights,
 * bloom, auto exposure, the display transform); medians, over the 4 s after the salvo, of the GPU's passes (the
 * particles' update, sort and draw, and the frame's span) from the graph's timestamps, and of the CPU's show and
 * effects sync per frame, with the particles alive and the burst lights lit.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class FireworksBenchmark {

    private static final double STEP = 1 / 60.0;

    @Test
    void denseFinale() throws InterruptedException {
        try (Stage s = new Stage(1920, 1080, false, 256, Stage.Look.FILM)) {
            System.out.println("fireworks benchmark on " + s.device.gpu().name());
            s.graph.timings(true);
            FireworksFilms.night(s);
            long site = Village.build(s);
            s.look(2, 1.7, 14, 2, 60, -250, 62);
            FireworkShow finale = FireworkShow.finale(2027, 0, 8, 120, 240);
            s.scene.update();
            s.scene.components().set(site, FireworkDisplay.TYPE, FireworkDisplay.of(finale));
            Map<String, List<Double>> passes = new TreeMap<>();
            List<Double> span = new ArrayList<>();
            List<Double> show = new ArrayList<>();
            List<Double> sync = new ArrayList<>();
            List<Double> alive = new ArrayList<>();
            List<Double> lit = new ArrayList<>();
            int frames = (int) (16 / STEP);
            for (int f = 0; f < frames; f++) {
                double t = f * STEP;
                long a = System.nanoTime();
                s.fireworks.update(STEP);
                long b = System.nanoTime();
                s.scene.update();
                s.lightSync.update();
                s.graph.beginFrame();
                long c = System.nanoTime();
                s.fx.update(STEP);
                long d = System.nanoTime();
                s.view.set(1, com.truesim.engine.render.MeshletRenderer.MODE_LIT);
                s.graph.submit(0, 0);
                // the 4 s after the salvo: the most shells bursting and burning at once
                if (t >= 11 && t < 15) {
                    show.add((b - a) / 1e6);
                    sync.add((d - c) / 1e6);
                    alive.add((double) s.particles.alive());
                    lit.add((double) s.fireworks.lit());
                    GpuTimings timings = s.graph.timings();
                    if (timings != null) {
                        span.add(timings.spanMillis());
                        Map<String, Double> frame = new TreeMap<>();
                        for (int i = 0; i < timings.count(); i++) {
                            double ms = timings.millis(i);
                            if (!Double.isNaN(ms)) {
                                frame.merge(group(timings.pass(i)), ms, Double::sum);
                            }
                        }
                        frame.forEach((k, v) -> passes.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
                    }
                }
            }
            s.device.waitIdle();
            System.out.printf(Locale.ROOT, "dense finale, 1920 x 1080: %d shells, particles alive (median) %.0f, "
                + "burst lights lit %.0f%n", s.fireworks.launched(), median(alive), median(lit));
            System.out.printf(Locale.ROOT, "  GPU frame span %.3f ms%n", median(span));
            passes.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-28s %.3f ms%n", k, median(v)));
            System.out.printf(Locale.ROOT, "  CPU: show %.3f ms, effects sync %.3f ms%n", median(show), median(sync));
        }
    }

    /** The passes grouped: the particles' update, events, sort, draw; the post effects; the rest by name. */
    private static String group(String pass) {
        if (pass.equals("particle update")) {
            return "particles: update";
        }
        if (pass.startsWith("particle sort")) {
            return "particles: sort";
        }
        if (pass.startsWith("particle draw") || pass.startsWith("particle half") || pass.startsWith("particle upsample")
            || pass.startsWith("particle lighting")) {
            return "particles: draw";
        }
        if (pass.startsWith("particle")) {
            return "particles: other";
        }
        if (pass.toLowerCase(Locale.ROOT).contains("bloom")) {
            return "bloom";
        }
        if (pass.toLowerCase(Locale.ROOT).contains("exposure")) {
            return "auto exposure";
        }
        return "other: " + pass.replaceAll("[0-9]+", "#");
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
