package com.takumi.lowendboost;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.ParticlesMode;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.SimpleOption;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * IMPORTANT LIMITATIONS:
 * - No client mod can "boost ping" in the sense of improving your route to a server.
 * - This mod focuses on reducing client-side render & simulation cost and keeping defaults consistent.
 */
public class LowEndBoostClient implements ClientModInitializer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path cfgPath = FabricLoader.getInstance().getConfigDir().resolve("lowendboost.json");

        JsonObject cfg = loadOrCreateConfig(cfgPath);

        if (!cfg.has("applyOnStartup") || !cfg.get("applyOnStartup").getAsBoolean()) {
            return;
        }

        // Apply once the client options exist.
        client.execute(() -> applyOptions(client.options, cfg));
    }

    private static JsonObject loadOrCreateConfig(Path path) {
        try {
            if (!Files.exists(path)) {
                // copy defaults from resources into config
                try (Reader r = new java.io.InputStreamReader(
                        LowEndBoostClient.class.getClassLoader().getResourceAsStream("lowendboost.default.json"),
                        StandardCharsets.UTF_8
                )) {
                    JsonObject obj = GSON.fromJson(r, JsonObject.class);
                    Files.createDirectories(path.getParent());
                    try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                        GSON.toJson(obj, w);
                    }
                    return obj;
                }
            }

            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject obj = GSON.fromJson(r, JsonObject.class);
                return obj == null ? new JsonObject() : obj;
            }
        } catch (Exception e) {
            // Fail safe: never crash the game because of config
            return new JsonObject();
        }
    }

    private static void applyOptions(GameOptions o, JsonObject cfg) {
        JsonObject video = cfg.has("video") ? cfg.getAsJsonObject("video") : new JsonObject();
        JsonObject gameplay = cfg.has("gameplay") ? cfg.getAsJsonObject("gameplay") : new JsonObject();

        // Helper: set SimpleOption safely
        java.util.function.BiConsumer<SimpleOption<Integer>, Integer> setInt =
                (opt, v) -> { try { opt.setValue(v); } catch (Throwable ignored) {} };
        java.util.function.BiConsumer<SimpleOption<Double>, Double> setDouble =
                (opt, v) -> { try { opt.setValue(v); } catch (Throwable ignored) {} };
        java.util.function.BiConsumer<SimpleOption<Boolean>, Boolean> setBool =
                (opt, v) -> { try { opt.setValue(v); } catch (Throwable ignored) {} };

        // Distances
        if (video.has("renderDistance")) {
            setInt.accept(o.getViewDistance(), clamp(video.get("renderDistance").getAsInt(), 2, 32));
        }
        if (video.has("simulationDistance")) {
            setInt.accept(o.getSimulationDistance(), clamp(video.get("simulationDistance").getAsInt(), 2, 32));
        }

        // Graphics
        if (video.has("graphicsMode")) {
            String gm = video.get("graphicsMode").getAsString();
            try {
                GraphicsMode mode = "FANCY".equalsIgnoreCase(gm) ? GraphicsMode.FANCY : GraphicsMode.FAST;
                o.getGraphicsMode().setValue(mode);
            } catch (Throwable ignored) {}
        }

        // Clouds
        if (video.has("clouds")) {
            String cm = video.get("clouds").getAsString();
            try {
                CloudRenderMode mode;
                if ("OFF".equalsIgnoreCase(cm)) mode = CloudRenderMode.OFF;
                else if ("FAST".equalsIgnoreCase(cm)) mode = CloudRenderMode.FAST;
                else mode = CloudRenderMode.FANCY;
                o.getCloudRenderMode().setValue(mode);
            } catch (Throwable ignored) {}
        }

        // Particles
        if (video.has("particles")) {
            String pm = video.get("particles").getAsString();
            try {
                ParticlesMode mode;
                if ("ALL".equalsIgnoreCase(pm)) mode = ParticlesMode.ALL;
                else if ("DECREASED".equalsIgnoreCase(pm)) mode = ParticlesMode.DECREASED;
                else mode = ParticlesMode.MINIMAL;
                o.getParticles().setValue(mode);
            } catch (Throwable ignored) {}
        }

        // Entity distance scaling (0.5..1.0 is typical)
        if (video.has("entityDistanceScaling")) {
            double v = video.get("entityDistanceScaling").getAsDouble();
            setDouble.accept(o.getEntityDistanceScaling(), clamp(v, 0.2, 1.0));
        }

        // Biome blend
        if (video.has("biomeBlendRadius")) {
            setInt.accept(o.getBiomeBlendRadius(), clamp(video.get("biomeBlendRadius").getAsInt(), 0, 7));
        }

        // Mipmaps
        if (video.has("mipmapLevels")) {
            setInt.accept(o.getMipmapLevels(), clamp(video.get("mipmapLevels").getAsInt(), 0, 4));
        }

        // FPS cap + VSync
        if (video.has("fpsLimit")) {
            setInt.accept(o.getMaxFps(), clamp(video.get("fpsLimit").getAsInt(), 10, 260));
        }
        if (video.has("vsync")) {
            setBool.accept(o.getEnableVsync(), video.get("vsync").getAsBoolean());
        }

        // Small QoL that can reduce perceived latency
        if (gameplay.has("reduceViewBob") && gameplay.get("reduceViewBob").getAsBoolean()) {
            try { o.getBobView().setValue(false); } catch (Throwable ignored) {}
        }

        // Telemetry toggle (if present)
        if (gameplay.has("disableTelemetry") && gameplay.get("disableTelemetry").getAsBoolean()) {
            try {
                // Option name has changed across versions; this is best-effort.
                java.lang.reflect.Method m = o.getClass().getDeclaredMethod("getTelemetryEnabled");
                m.setAccessible(true);
                Object opt = m.invoke(o);
                if (opt instanceof SimpleOption<?> so) {
                    @SuppressWarnings("unchecked")
                    SimpleOption<Boolean> b = (SimpleOption<Boolean>) so;
                    b.setValue(false);
                }
            } catch (Throwable ignored) {}
        }

        // Persist options to disk
        try { o.write(); } catch (Throwable ignored) {}
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
