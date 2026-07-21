package com.ysm.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Generates MythicMobs entity YAML from YSMParser output.
 *
 * <p>Scans:
 * <ul>
 *   <li>{@code ysm.json}  — entity base type</li>
 *   <li>{@code animations/} — animation→skill mappings</li>
 *   <li>{@code controller/} — state→AI goal templates</li>
 *   <li>{@code sounds/}     — sound event list</li>
 * </ul>
 */
public final class YsmToMythicMobs {

    private YsmToMythicMobs() {}

    public static void convert(Path parsedDir, Path mythicmobsDir, String modelName) throws IOException {
        convert(parsedDir, mythicmobsDir, modelName,
                Path.of("bbmodels").resolve(modelName + ".bbmodel"));
    }

    public static void convert(Path parsedDir, Path mythicmobsDir, String modelName,
                               Path bbmodelPath) throws IOException {
        Files.createDirectories(mythicmobsDir);

        var ctx = new Context(modelName);
        ctx.parseYsmJson(parsedDir.resolve("ysm.json"));
        ctx.parseAnimations(parsedDir.resolve("animations"));
        ctx.parseController(parsedDir.resolve("controller"));
        ctx.parseSounds(parsedDir.resolve("sounds"));
        ctx.detectBbmodelHitboxes(bbmodelPath);

        Path mobFile = mythicmobsDir.resolve(modelName + ".yml");
        Files.writeString(mobFile, ctx.buildYaml(), StandardCharsets.UTF_8);
    }

    // ── context ──

    static final class Context {
        final String modelName;
        String entityType = "ZOMBIE";       // from ysm.json or fallback
        String displayName;

        final Map<String, AnimationInfo> animations = new LinkedHashMap<>();
        final List<String> controllerStates = new ArrayList<>();
        final List<String> soundFiles = new ArrayList<>();
        boolean hasHitbox, hasSeat, hasMount;

        Context(String modelName) {
            this.modelName = modelName;
            this.displayName = modelName;
        }

        // ── ysm.json ──

        void parseYsmJson(Path path) {
            if (!Files.isRegularFile(path)) return;
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("name")) displayName = root.get("name").getAsString();
                if (root.has("type"))  entityType = mapYsmType(root.get("type").getAsString());
                if (root.has("health")) {
                    // could store for default health
                }
            } catch (IOException ignored) {}
        }

        static String mapYsmType(String ysmType) {
            return switch (ysmType.toLowerCase()) {
                case "player" -> "PLAYER";
                case "skeleton" -> "SKELETON";
                case "zombie" -> "ZOMBIE";
                case "spider" -> "SPIDER";
                case "creeper" -> "CREEPER";
                default -> "ZOMBIE";
            };
        }

        // ── animations ──

        void parseAnimations(Path dir) {
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json"))
                     .forEach(this::parseAnimationFile);
            } catch (IOException ignored) {}
        }

        void parseAnimationFile(Path path) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (!root.has("animations")) return;
                for (var entry : root.getAsJsonObject("animations").entrySet()) {
                    String animName = entry.getKey();
                    JsonObject data = entry.getValue().getAsJsonObject();
                    float len = data.has("animation_length") ? data.get("animation_length").getAsFloat() : 1f;
                    boolean loop = data.has("loop") && data.get("loop").getAsBoolean();
                    animations.put(animName, new AnimationInfo(animName, len, loop));
                }
            } catch (IOException ignored) {}
        }

        // ── controller ──

        void parseController(Path dir) {
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".json"))
                     .forEach(this::parseControllerFile);
            } catch (IOException ignored) {}
        }

        void parseControllerFile(Path path) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("animation_controllers")) {
                    for (var entry : root.getAsJsonObject("animation_controllers").entrySet()) {
                        JsonObject ctrl = entry.getValue().getAsJsonObject();
                        if (ctrl.has("states")) {
                            for (var stEntry : ctrl.getAsJsonObject("states").entrySet()) {
                                String stateName = stEntry.getKey();
                                controllerStates.add(stateName);
                            }
                        }
                    }
                }
            } catch (IOException ignored) {}
        }

        // ── sounds ──

        void parseSounds(Path dir) {
            if (!Files.isDirectory(dir)) return;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase();
                    return n.endsWith(".ogg") || n.endsWith(".wav");
                }).forEach(f -> soundFiles.add(f.getFileName().toString()));
            } catch (IOException ignored) {}
        }

        // ── .bbmodel hitbox detection ──

        void detectBbmodelHitboxes(Path path) {
            if (!Files.isRegularFile(path)) return;
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
                if (root.has("outliner")) {
                    scanBones(root.getAsJsonArray("outliner"));
                }
            } catch (IOException ignored) {}
        }

        void scanBombs(com.google.gson.JsonArray arr) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject g = el.getAsJsonObject();
                String name = g.has("name") ? g.get("name").getAsString() : "";
                if (name.toLowerCase().startsWith("hit_")) hasHitbox = true;
                if (name.equalsIgnoreCase("seat")) hasSeat = true;
                if (name.equalsIgnoreCase("mount")) hasMount = true;
                if (g.has("children")) scanBones(g.getAsJsonArray("children"));
            }
        }

        void scanBones(com.google.gson.JsonArray arr) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject g = el.getAsJsonObject();
                String name = g.has("name") ? g.get("name").getAsString() : "";
                if (name.toLowerCase().startsWith("hit_")) hasHitbox = true;
                if (name.equalsIgnoreCase("seat")) hasSeat = true;
                if (name.equalsIgnoreCase("mount")) hasMount = true;
                if (g.has("children")) scanBones(g.getAsJsonArray("children"));
            }
        }

        // ── YAML builder ──

        String buildYaml() {
            var sb = new StringBuilder();
            sb.append("# MythicMobs entity for ").append(displayName).append("\n");
            sb.append("# Generated by ysm-tool → https://github.com/OpenYSM/YSMParser\n\n");

            sb.append(modelName).append(":\n");
            sb.append("  Type: ").append(entityType).append("\n");
            sb.append("  Display: '").append(displayName).append("'\n");
            sb.append("  Health: 20\n");
            sb.append("  Damage: 2\n");
            sb.append("  Model:\n");
            sb.append("    Id: ").append(modelName).append("\n");
            sb.append("    Scale: 1.0\n");
            sb.append("  # ViewRadius: 64\n");
            sb.append("  # KnockbackResistance: 0.3\n");
            sb.append("  # FollowRange: 12\n");
            sb.append("  # MovementSpeed: 0.3\n");

            if (hasHitbox) {
                sb.append("  Hitbox:\n");
                sb.append("    Width: 0.6\n");
                sb.append("    Height: 1.8\n");
            }

            if (hasMount) {
                sb.append("  Mount: <ENTITY_ID>\n");
            }

            sb.append("\n");

            // ── Sounds ──
            if (!soundFiles.isEmpty()) {
                sb.append("  # Detected sound files in YSM model\n");
                for (String s : soundFiles) {
                    String base = s.replaceFirst("\\.[^.]+$", "");
                    sb.append("  # Sounds:\n");
                    sb.append("  # - sound:entity.").append(modelName).append(".").append(base)
                      .append("  ← ").append(s).append("\n");
                }
                sb.append("\n");
            }

            // ── Skills (animation / controller mapping) ──
            sb.append("  # === Animation → Skill Mapping ===\n");
            sb.append("  # (uncomment and replace placeholder skill names)\n");
            sb.append("  Skills:\n");

            List<AnimationInfo> sorted = new ArrayList<>(animations.values());
            // auto-classify by name heuristics
            for (AnimationInfo anim : sorted) {
                String animLower = anim.name.toLowerCase();
                if (animLower.contains("idle")) {
                    sb.append("  # - skill:Idle @self ~onSpawn");
                } else if (animLower.contains("walk") || animLower.contains("run")) {
                    sb.append("  # - skill:Walk @self ~onTimer:20");
                } else if (animLower.contains("attack") || animLower.contains("hit")) {
                    sb.append("  # - skill:Attack @self ~onAttack");
                } else if (animLower.contains("hurt") || animLower.contains("damage")) {
                    sb.append("  # - skill:Hurt @self ~onDamaged");
                } else if (animLower.contains("death") || animLower.contains("die")) {
                    sb.append("  # - skill:Death @self ~onDeath");
                } else {
                    sb.append("  # - skill:").append(anim.name.replace('.', '_'))
                      .append(" @self ~onTimer:20");
                }
                sb.append("    ← ").append(anim.name)
                  .append(" (").append(anim.length).append("s, ")
                  .append(anim.loop ? "loop" : "once").append(")\n");
            }

            if (!controllerStates.isEmpty()) {
                sb.append("\n  # === YSM Controller States ===\n");
                for (String state : controllerStates) {
                    sb.append("  # state: ").append(state).append("\n");
                }
            }

            sb.append("\n  # === AI Goals ===\n");
            sb.append("  AIGoalSelectors:\n");
            sb.append("  - clear\n");
            sb.append("  - randomLookaround\n");

            boolean hasWalk = sorted.stream().anyMatch(a -> 
                a.name.toLowerCase().contains("walk") || a.name.toLowerCase().contains("run"));
            if (hasWalk) {
                sb.append("  # - gotoPlayer  ← walk animation detected, needs Conditional AI:\n");
            }

            sb.append("\n  AITargetSelectors:\n");
            sb.append("  - clear\n");
            sb.append("  - players\n");

            sb.append("\n  Options:\n");
            sb.append("    AlwaysShowName: false\n");
            sb.append("    # PreventOtherDrops: true\n");
            sb.append("    # PreventRandomEquipment: true\n");
            sb.append("    # PreventSunburn: true\n");

            return sb.toString();
        }
    }

    record AnimationInfo(String name, float length, boolean loop) {}
}
