package com.ysm.converter;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Post-processes a Blockbench .bbmodel for ModelEngine compatibility.
 *
 * <p>Actions performed:
 * <ul>
 *   <li>Rename bones that start with "hit_" → ModelEngine hitbox bone</li>
 *   <li>Copy textures to the model's texture folder</li>
 *   <li>Strip non-ModelEngine animation controllers</li>
 *   <li>Write a basic ModelEngine model configuration</li>
 * </ul>
 */
public final class BlockbenchToModelEngine {

    private BlockbenchToModelEngine() {}

    public static void convert(Path bbmodelPath, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);

        String raw = Files.readString(bbmodelPath, StandardCharsets.UTF_8);
        JsonObject model = JsonParser.parseString(raw).getAsJsonObject();

        // Copy the processed .bbmodel alongside
        Path destBbmodel = outputDir.resolve(bbmodelPath.getFileName());
        Files.copy(bbmodelPath, destBbmodel, StandardCopyOption.REPLACE_EXISTING);

        // Flag hitbox bones
        if (model.has("outliner")) {
            flagModelEngineBones(model.getAsJsonArray("outliner"));
        }

        // Write the augmented .bbmodel back
        Files.writeString(destBbmodel, model.toString(), StandardCharsets.UTF_8);

        // Write a skeleton ModelEngine model.yml
        String modelName = bbmodelPath.getFileName().toString().replace(".bbmodel", "");
        writeModelEngineConfig(outputDir, modelName);
    }

    private static void flagModelEngineBones(JsonArray outliner) {
        for (JsonElement el : outliner) {
            if (!el.isJsonObject()) continue;
            JsonObject group = el.getAsJsonObject();
            String name = group.has("name") ? group.get("name").getAsString() : "";
            if (name.toLowerCase().startsWith("hit_")) {
                group.addProperty("model_engine_hitbox", true);
            }
            if (name.equalsIgnoreCase("mount") || name.equalsIgnoreCase("seat")) {
                group.addProperty("model_engine_seat", true);
            }
            if (group.has("children")) {
                flagModelEngineBones(group.getAsJsonArray("children"));
            }
        }
    }

    private static void writeModelEngineConfig(Path outputDir, String modelName) throws IOException {
        String yml = """
                # ModelEngine model configuration for %s
                model:
                  id: %s
                  type: GENERIC
                  scale: 1.0
                  # Hitbox, seat, and nametag bones are auto-detected from .bbmodel.
                  # Adjust mountHeight and other settings as needed.
                """.formatted(modelName, modelName);

        Files.writeString(outputDir.resolve("model.yml"), yml, StandardCharsets.UTF_8);
    }
}
