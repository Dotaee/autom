package fr.autominer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Sauvegarde / chargement du circuit dans config/autominer-route.json. */
final class RouteStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RouteStore() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("autominer-route.json");
    }

    static List<RoutePoint> load() {
        List<RoutePoint> out = new ArrayList<>();
        Path f = file();
        if (!Files.exists(f)) return out;
        try {
            JsonArray arr = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonArray.class);
            if (arr == null) return out;
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                out.add(new RoutePoint(
                        o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                        o.get("pitch").getAsFloat(), o.get("mine").getAsBoolean()));
            }
        } catch (IOException | RuntimeException ex) {
            out.clear(); // fichier illisible : on repart de zéro
        }
        return out;
    }

    static void save(List<RoutePoint> route) {
        JsonArray arr = new JsonArray();
        for (RoutePoint p : route) {
            JsonObject o = new JsonObject();
            o.addProperty("x", p.x());
            o.addProperty("y", p.y());
            o.addProperty("z", p.z());
            o.addProperty("pitch", p.pitch());
            o.addProperty("mine", p.mineLeg());
            arr.add(o);
        }
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(arr), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // la sauvegarde est un confort, pas une nécessité
        }
    }
}
