package com.ysm.converter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts YSMParser output (Bedrock Geometry + GeckoLib Animation JSON)
 * into a Blockbench .bbmodel project.
 */
public final class YsmToBlockbench {

    private static final float FLAT_LAYER_MAX_THICKNESS = 0.0101f;

    private YsmToBlockbench() {}

    public static void convert(Path parsedDir, Path bbmodelPath) throws IOException {
        MOLANG_WARNED.clear();
        molangCount = 0;

        var model = new BbModel();
        Path fileName = parsedDir.getFileName();
        model.setName(fileName == null ? "ysm_model" : fileName.toString());

        Map<String, String> geometryTextures = readGeometryTextureAssignments(parsedDir);
        Set<String> primaryGeometryPaths = readPlayerPaths(parsedDir, "model", ".json", true);
        Set<String> primaryAnimationPaths = readPlayerPaths(parsedDir, "animation", ".json", false);

        // 1) Textures. Only textures used by the primary player model are embedded.
        Path texturesDir = findDir(parsedDir, "textures");
        for (Path texture : orderedTextureFiles(parsedDir, texturesDir, geometryTextures,
                primaryGeometryPaths)) {
            model.addTexture(texture);
        }

        // 2) Geometry
        Path modelsDir = findDir(parsedDir, "models");
        if (modelsDir != null) {
            List<Path> geometryFiles;
            try (var files = Files.list(modelsDir)) {
                geometryFiles = files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
            for (Path geometryFile : geometryFiles) {
                String relativePath = normalizeRelativePath(parsedDir.relativize(geometryFile).toString());
                if (!primaryGeometryPaths.isEmpty() && !primaryGeometryPaths.contains(relativePath)) continue;
                int textureIndex = model.findTextureIndex(geometryTextures.get(relativePath));
                importBedrockGeometry(geometryFile, model, textureIndex);
            }
        }

        // 3) Animation keyframes
        Path animsDir = findDir(parsedDir, "animations");
        if (animsDir != null) {
            List<Path> animationFiles;
            try (var files = Files.list(animsDir)) {
                animationFiles = files.filter(f -> f.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
            for (Path animationFile : animationFiles) {
                String relativePath = normalizeRelativePath(parsedDir.relativize(animationFile).toString());
                if (!primaryAnimationPaths.isEmpty() && !primaryAnimationPaths.contains(relativePath)) continue;
                importGeckoLibAnimation(animationFile, model);
            }
        }

        model.assignFlatLayerRenderPriorities(FLAT_LAYER_MAX_THICKNESS);
        model.writeTo(bbmodelPath);
    }

    private static Path findDir(Path root, String name) {
        Path dir = root.resolve(name);
        return Files.isDirectory(dir) ? dir : null;
    }

    private static Map<String, String> readGeometryTextureAssignments(Path parsedDir) {
        Map<String, String> assignments = new LinkedHashMap<>();
        Path manifestPath = parsedDir.resolve("ysm.json");
        if (!Files.isRegularFile(manifestPath)) return assignments;

        try {
            JsonElement manifest = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8));
            collectModelTextureAssignments(manifest, assignments);
        } catch (IOException | RuntimeException e) {
            System.err.println("  读取 YSM 纹理映射失败，使用默认纹理: " + e.getMessage());
        }
        return assignments;
    }

    private static Set<String> readPlayerPaths(Path parsedDir, String field,
                                               String suffix, boolean preferMain) {
        Set<String> paths = new LinkedHashSet<>();
        Path manifestPath = parsedDir.resolve("ysm.json");
        if (!Files.isRegularFile(manifestPath)) return paths;

        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement filesElement = root.get("files");
            if (filesElement == null || !filesElement.isJsonObject()) return paths;
            JsonElement playerElement = filesElement.getAsJsonObject().get("player");
            if (playerElement == null || !playerElement.isJsonObject()) return paths;
            JsonElement fieldElement = playerElement.getAsJsonObject().get(field);
            if (fieldElement == null) return paths;

            if (preferMain && fieldElement.isJsonObject()
                    && fieldElement.getAsJsonObject().has("main")) {
                fieldElement = fieldElement.getAsJsonObject().get("main");
            }
            for (String path : collectPathStrings(fieldElement, suffix)) {
                paths.add(normalizeRelativePath(path));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("  读取 YSM player." + field + " 映射失败: " + e.getMessage());
        }
        return paths;
    }

    private static void collectModelTextureAssignments(JsonElement element,
                                                       Map<String, String> assignments) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectModelTextureAssignments(child, assignments);
            }
            return;
        }
        if (!element.isJsonObject()) return;

        JsonObject object = element.getAsJsonObject();
        if (object.has("model") && object.has("texture")) {
            List<String> models = collectPathStrings(object.get("model"), ".json");
            List<String> textures = collectPathStrings(object.get("texture"), ".png");
            if (!textures.isEmpty()) {
                String texture = normalizeRelativePath(textures.get(0));
                for (String model : models) {
                    assignments.put(normalizeRelativePath(model), texture);
                }
            }
        }
        for (var entry : object.entrySet()) {
            collectModelTextureAssignments(entry.getValue(), assignments);
        }
    }

    private static List<String> collectPathStrings(JsonElement element, String suffix) {
        List<String> paths = new ArrayList<>();
        collectPathStrings(element, suffix, paths);
        return paths;
    }

    private static void collectPathStrings(JsonElement element, String suffix, List<String> output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            String value = element.getAsString();
            if (value.toLowerCase().endsWith(suffix)) output.add(value);
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectPathStrings(child, suffix, output);
            }
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                collectPathStrings(entry.getValue(), suffix, output);
            }
        }
    }

    private static List<Path> orderedTextureFiles(Path parsedDir, Path texturesDir,
                                                  Map<String, String> geometryTextures,
                                                  Set<String> primaryGeometryPaths)
            throws IOException {
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        Path normalizedRoot = parsedDir.toAbsolutePath().normalize();

        if (primaryGeometryPaths.isEmpty()) {
            for (String textureRef : geometryTextures.values()) {
                addTextureIfPresent(normalizedRoot, textureRef, ordered);
            }
        } else {
            for (String geometryPath : primaryGeometryPaths) {
                addTextureIfPresent(normalizedRoot, geometryTextures.get(geometryPath), ordered);
            }
        }

        // Projects without a usable manifest fall back to every exported texture.
        if (ordered.isEmpty() && texturesDir != null) {
            try (var files = Files.list(texturesDir)) {
                files.filter(f -> f.getFileName().toString().toLowerCase().endsWith(".png"))
                        .sorted()
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(ordered::add);
            }
        }
        return new ArrayList<>(ordered);
    }

    private static void addTextureIfPresent(Path root, String textureRef,
                                            Set<Path> output) {
        if (textureRef == null || textureRef.isBlank()) return;
        Path texture = root.resolve(textureRef).normalize();
        if (texture.startsWith(root) && Files.isRegularFile(texture)) output.add(texture);
    }

    private static String normalizeRelativePath(String path) {
        return path.replace('\\', '/');
    }

    /**
     * Reads a Bedrock-format geometry JSON and builds Blockbench elements/groups.
     *
     * <p>Expected structure (per bone):
     * <pre>{@code
     * {
     *   "minecraft:geometry": [{
     *     "bones": [
     *       {
     *         "name": "body",
     *         "parent": "root",
     *         "pivot": [0, 12, 0],
     *         "cubes": [{
     *           "origin": [-4, 0, -2],
     *           "size": [8, 12, 4],
     *           "uv": [0, 0]
     *         }]
     *       }
     *     ]
     *   }]
     * }</pre>
     */
    private static void importBedrockGeometry(Path geoPath, BbModel model, int textureIndex) {
        try {
            String raw = Files.readString(geoPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            JsonElement geometriesElement = root.get("minecraft:geometry");
            if (geometriesElement == null || !geometriesElement.isJsonArray()) return;
            for (JsonElement geometryElement : geometriesElement.getAsJsonArray()) {
                if (!geometryElement.isJsonObject()) continue;
                JsonObject geometry = geometryElement.getAsJsonObject();

                JsonElement descriptionElement = geometry.get("description");
                if (descriptionElement != null && descriptionElement.isJsonObject()) {
                    JsonObject description = descriptionElement.getAsJsonObject();
                    float width = parseFloat(description.get("texture_width"), 16f,
                            geoPath.getFileName() + " texture_width");
                    float height = parseFloat(description.get("texture_height"), 16f,
                            geoPath.getFileName() + " texture_height");
                    model.setResolution(width, height);
                }

                JsonElement bonesElement = geometry.get("bones");
                if (bonesElement == null || !bonesElement.isJsonArray()) continue;
                for (JsonElement boneElement : bonesElement.getAsJsonArray()) {
                    if (!boneElement.isJsonObject()) continue;
                    JsonObject bone = boneElement.getAsJsonObject();
                    String name = safeString(bone, "name", "bone");
                    String parent = safeString(bone, "parent", null);
                    float[] pivot = floatArray(bone, "pivot", 3);
                    float boneInflate = bone.has("inflate")
                            ? parseFloat(bone.get("inflate"), 0f, name + " bone inflate") : 0f;
                    boolean boneMirror = parseBoolean(bone.get("mirror"), false);

                    BbGroup group = model.getOrCreateGroup(name, parent);
                    group.origin = bedrockPivotToBlockbench(pivot);
                    group.rotation = bedrockRotationToBlockbench(floatArray(bone, "rotation", 3));

                    JsonElement cubesElement = bone.get("cubes");
                    if (cubesElement == null || !cubesElement.isJsonArray()) continue;
                    for (JsonElement cubeElement : cubesElement.getAsJsonArray()) {
                        if (!cubeElement.isJsonObject()) continue;
                        JsonObject cube = cubeElement.getAsJsonObject();
                        float[] origin = floatArray(cube, "origin", 3);
                        float[] size = floatArray(cube, "size", 3);

                        BbElement element = new BbElement();
                        element.name = name + "_cube_" + group.elements.size();
                        element.color = group.color;
                        element.from[0] = -(origin[0] + size[0]);
                        element.from[1] = origin[1];
                        element.from[2] = origin[2];
                        element.to[0] = element.from[0] + size[0];
                        element.to[1] = element.from[1] + size[1];
                        element.to[2] = element.from[2] + size[2];
                        if (isFlatTextureLayer(size)) element.renderOrder = "in_front";
                        float[] cubePivot = cube.has("pivot")
                                ? floatArray(cube, "pivot", 3) : pivot.clone();
                        element.origin = bedrockPivotToBlockbench(cubePivot);
                        element.rotation = bedrockRotationToBlockbench(floatArray(cube, "rotation", 3));
                        element.inflate = cube.has("inflate")
                                ? parseFloat(cube.get("inflate"), boneInflate, name + " cube inflate")
                                : boneInflate;
                        element.mirrorUv = parseBoolean(cube.get("mirror"), boneMirror);
                        applyCubeFaces(cube, size, element, textureIndex);

                        group.elements.add(element);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("  读取 Bedrock geometry 失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isFlatTextureLayer(float[] size) {
        return Math.abs(size[0]) <= FLAT_LAYER_MAX_THICKNESS
                || Math.abs(size[1]) <= FLAT_LAYER_MAX_THICKNESS
                || Math.abs(size[2]) <= FLAT_LAYER_MAX_THICKNESS;
    }

    private static float[] bedrockPivotToBlockbench(float[] values) {
        return new float[]{-values[0], values[1], values[2]};
    }

    private static float[] bedrockRotationToBlockbench(float[] values) {
        return new float[]{-values[0], -values[1], values[2]};
    }

    private static void applyCubeFaces(JsonObject cube, float[] size, BbElement element,
                                       int textureIndex) {
        JsonElement uvElement = cube.get("uv");
        if (uvElement != null && uvElement.isJsonObject()) {
            JsonObject perFace = uvElement.getAsJsonObject();
            if (hasFaceUv(perFace)) {
                element.faces.north = faceFromUv(perFace.get("north"), size[0], size[1],
                        "north UV", textureIndex, false);
                element.faces.south = faceFromUv(perFace.get("south"), size[0], size[1],
                        "south UV", textureIndex, false);
                element.faces.east = faceFromUv(perFace.get("east"), size[2], size[1],
                        "east UV", textureIndex, false);
                element.faces.west = faceFromUv(perFace.get("west"), size[2], size[1],
                        "west UV", textureIndex, false);
                element.faces.up = faceFromUv(perFace.get("up"), size[0], size[2],
                        "up UV", textureIndex, true);
                element.faces.down = faceFromUv(perFace.get("down"), size[0], size[2],
                        "down UV", textureIndex, true);
                removeCoincidentBackFace(size, element);
                return;
            }
        }

        float[] uv = floatArray(cube, "uv", 2);
        element.faces.north = boxUvFace(uv, size[0], size[1], textureIndex);
        element.faces.south = boxUvFace(uv, size[0], size[1], textureIndex);
        element.faces.east = boxUvFace(uv, size[2], size[1], textureIndex);
        element.faces.west = boxUvFace(uv, size[2], size[1], textureIndex);
        element.faces.up = boxUvFace(uv, size[0], size[2], textureIndex);
        element.faces.down = boxUvFace(uv, size[0], size[2], textureIndex);
        removeCoincidentBackFace(size, element);
    }

    private static void removeCoincidentBackFace(float[] size, BbElement element) {
        final float zeroThickness = 0.0000001f;
        if (Math.abs(size[0]) <= zeroThickness && element.faces.east != null) {
            element.faces.east.texture = -1;
        }
        if (Math.abs(size[1]) <= zeroThickness && element.faces.down != null) {
            element.faces.down.texture = -1;
        }
        if (Math.abs(size[2]) <= zeroThickness && element.faces.south != null) {
            element.faces.south.texture = -1;
        }
    }

    private static boolean hasFaceUv(JsonObject uv) {
        return uv.has("north") || uv.has("south") || uv.has("east")
                || uv.has("west") || uv.has("up") || uv.has("down");
    }

    private static BbFace faceFromUv(JsonElement faceElement, float defaultWidth,
                                     float defaultHeight, String context, int textureIndex,
                                     boolean reverseUv) {
        if (faceElement == null || faceElement.isJsonNull()) return null;

        float[] uv;
        float[] uvSize = {defaultWidth, defaultHeight};
        if (faceElement.isJsonObject()) {
            JsonObject faceObject = faceElement.getAsJsonObject();
            uv = floatArray(faceObject, "uv", 2);
            if (faceObject.has("uv_size")) {
                uvSize = fixedFloatArray(faceObject.get("uv_size"), 2, 0f, context + " size");
            }
        } else {
            uv = fixedFloatArray(faceElement, 2, 0f, context);
        }
        return boxUvFace(uv, uvSize[0], uvSize[1], textureIndex, reverseUv);
    }

    private static BbFace boxUvFace(float[] uv, float width, float height, int textureIndex) {
        return boxUvFace(uv, width, height, textureIndex, false);
    }

    private static BbFace boxUvFace(float[] uv, float width, float height,
                                    int textureIndex, boolean reverseUv) {
        BbFace face = new BbFace();
        if (reverseUv) {
            face.uv[0] = uv[0] + width;
            face.uv[1] = uv[1] + height;
            face.uv[2] = uv[0];
            face.uv[3] = uv[1];
        } else {
            face.uv[0] = uv[0];
            face.uv[1] = uv[1];
            face.uv[2] = uv[0] + width;
            face.uv[3] = uv[1] + height;
        }
        face.texture = textureIndex;
        return face;
    }

    /**
     * Reads GeckoLib animation JSON and converts keyframes to Blockbench timeline.
     *
     * <p>Expected structure:
     * <pre>{@code
     * {
     *   "format_version": "1.8.0",
     *   "animations": {
     *     "animation.model.idle": {
     *       "animation_length": 2.0,
     *       "loop": true,
     *       "bones": {
     *         "head": {
     *           "rotation": {
     *             "0.0": [10, 0, 0],
     *             "0.5": [-5, 0, 0],
     *             "1.0": [10, 0, 0]
     *           }
     *         }
     *       }
     *     }
     *   }
     * }</pre>
     */
    private static void importGeckoLibAnimation(Path animPath, BbModel model) {
        try {
            String raw = Files.readString(animPath, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();

            JsonElement animationsElement = root.get("animations");
            if (animationsElement == null || !animationsElement.isJsonObject()) return;
            JsonObject animations = animationsElement.getAsJsonObject();

            for (var animEntry : animations.entrySet()) {
                String animName = animEntry.getKey();
                if (!animEntry.getValue().isJsonObject()) {
                    warnMolang(animName + " 动画定义不是对象: " + shortValue(animEntry.getValue()));
                    continue;
                }
                JsonObject animData = animEntry.getValue().getAsJsonObject();

                BbAnimation bbAnim = model.getOrCreateAnimation(animName);
                if (animData.has("animation_length")) {
                    float length = parseFloat(animData.get("animation_length"), Float.NaN,
                            animName + " 动画时长");
                    if (Float.isFinite(length) && length >= 0f) bbAnim.length = length;
                }
                if (animData.has("loop")) {
                    bbAnim.loopMode = parseLoopMode(animData.get("loop"), bbAnim.loopMode);
                }

                JsonElement bonesElement = animData.get("bones");
                if (bonesElement == null || !bonesElement.isJsonObject()) continue;
                JsonObject bones = bonesElement.getAsJsonObject();

                for (var boneEntry : bones.entrySet()) {
                    String boneName = boneEntry.getKey();
                    if (!boneEntry.getValue().isJsonObject()) {
                        warnMolang(animName + "/" + boneName + " 骨骼动画不是对象");
                        continue;
                    }
                    JsonObject boneData = boneEntry.getValue().getAsJsonObject();
                    BbAnimator animator = bbAnim.getOrCreateAnimator(boneName);

                    for (var channelEntry : boneData.entrySet()) {
                        String channel = channelEntry.getKey();
                        if (!isTransformChannel(channel)) continue;
                        if ("scale".equals(channel) && isDefaultControlAnimation(animName)) {
                            applyDefaultVisibility(model, boneName, channelEntry.getValue());
                        }
                        importAnimationChannel(animName, boneName, channel,
                                channelEntry.getValue(), animator, bbAnim);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("  读取 GeckoLib 动画失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isDefaultControlAnimation(String animationName) {
        return animationName != null && animationName.matches("pre_parallel[0-7]");
    }

    private static void applyDefaultVisibility(BbModel model, String boneName,
                                               JsonElement channelData) {
        float[] scale = evaluateDefaultScale(channelData);
        if (scale == null) return;
        boolean hidden = Math.abs(scale[0]) < 0.000001f
                && Math.abs(scale[1]) < 0.000001f
                && Math.abs(scale[2]) < 0.000001f;
        if (hidden) {
            BbGroup group = model.groupMap.get(boneName);
            if (group != null) group.visible = false;
        }
    }

    private static float[] evaluateDefaultScale(JsonElement channelData) {
        JsonElement value = channelData;
        if (value == null || value.isJsonNull()) return null;

        if (value.isJsonObject() && !isDirectVectorObject(value.getAsJsonObject())) {
            JsonObject keyframes = value.getAsJsonObject();
            value = keyframes.get("0.0");
            if (value == null) value = keyframes.get("0");
            if (value == null) return null;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("post")) value = object.get("post");
            else if (object.has("pre")) value = object.get("pre");
            else if (object.has("vector")) value = object.get("vector");
        }

        if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            float[] result = {1f, 1f, 1f};
            int count = Math.min(3, array.size());
            for (int i = 0; i < count; i++) {
                Float component = evaluateDefaultScalar(array.get(i));
                if (component == null) return null;
                result[i] = component;
            }
            return result;
        }

        Float scalar = evaluateDefaultScalar(value);
        return scalar == null ? null : new float[]{scalar, scalar, scalar};
    }

    private static Float evaluateDefaultScalar(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        String expression = element.getAsString().trim().toLowerCase(Locale.ROOT)
                .replace(" ", "");
        if (expression.isEmpty()) return null;

        while (expression.startsWith("(") && expression.endsWith(")")
                && enclosesWholeExpression(expression)) {
            expression = expression.substring(1, expression.length() - 1);
        }

        try {
            return Float.parseFloat(expression);
        } catch (NumberFormatException ignored) {
            // Continue with the small default-variable expression subset below.
        }

        if (expression.startsWith("!")) {
            Float value = evaluateDefaultScalarText(expression.substring(1));
            return value == null ? null : (value == 0f ? 1f : 0f);
        }

        String[] operators = {"==", "!=", ">=", "<=", ">", "<"};
        for (String operator : operators) {
            int index = expression.indexOf(operator);
            if (index <= 0) continue;
            Float left = evaluateDefaultScalarText(expression.substring(0, index));
            Float right = evaluateDefaultScalarText(expression.substring(index + operator.length()));
            if (left == null || right == null) return null;
            return switch (operator) {
                case "==" -> left.floatValue() == right.floatValue() ? 1f : 0f;
                case "!=" -> left.floatValue() != right.floatValue() ? 1f : 0f;
                case ">=" -> left >= right ? 1f : 0f;
                case "<=" -> left <= right ? 1f : 0f;
                case ">" -> left > right ? 1f : 0f;
                case "<" -> left < right ? 1f : 0f;
                default -> null;
            };
        }
        return evaluateDefaultScalarText(expression);
    }

    private static Float evaluateDefaultScalarText(String expression) {
        String value = expression;
        while (value.startsWith("(") && value.endsWith(")")
                && enclosesWholeExpression(value)) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.matches("(?:v|q|query|ysm)\\.[a-z0-9_]+")) return 0f;
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean enclosesWholeExpression(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char character = expression.charAt(i);
            if (character == '(') depth++;
            else if (character == ')' && --depth == 0 && i < expression.length() - 1) return false;
        }
        return depth == 0;
    }

    private static boolean isTransformChannel(String channel) {
        return "rotation".equals(channel) || "position".equals(channel) || "scale".equals(channel);
    }

    private static void importAnimationChannel(String animName, String boneName, String channel,
                                               JsonElement channelData, BbAnimator animator,
                                               BbAnimation animation) {
        String context = animName + "/" + boneName + "/" + channel;
        if (channelData == null || channelData.isJsonNull()) return;

        if (!channelData.isJsonObject() || isDirectVectorObject(channelData.getAsJsonObject())) {
            putChannel(animator, channel, 0f,
                    parseAnimationKeyframe(channelData, channel, context + " 静态值"));
            return;
        }

        JsonObject keyframes = channelData.getAsJsonObject();
        for (var keyframeEntry : keyframes.entrySet()) {
            Float time = parseKeyframeTime(keyframeEntry.getKey(), context);
            if (time == null) continue;

            BbVectorKeyframe values = parseAnimationKeyframe(keyframeEntry.getValue(), channel,
                    context + " @ " + keyframeEntry.getKey());
            putChannel(animator, channel, time, values);
            animation.length = Math.max(animation.length, time);
        }
    }

    private static boolean isDirectVectorObject(JsonObject object) {
        return object.has("post") || object.has("pre") || object.has("vector")
                || object.has("x") || object.has("y") || object.has("z");
    }

    private static Float parseKeyframeTime(String rawTime, String context) {
        try {
            float time = Float.parseFloat(rawTime);
            if (Float.isFinite(time)) return time;
        } catch (NumberFormatException ignored) {
            // Handled below as an unsupported Molang/dynamic key.
        }
        warnMolang(context + " 关键帧时间: " + rawTime);
        return null;
    }

    private static BbVectorKeyframe parseAnimationKeyframe(JsonElement element, String channel,
                                                            String context) {
        float defaultValue = "scale".equals(channel) ? 1f : 0f;
        BbVectorKeyframe keyframe = new BbVectorKeyframe();
        if (element != null && element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("pre")) {
                keyframe.pre = parseAnimationVector(object.get("pre"), defaultValue, context + ".pre");
            }
            if (object.has("post")) {
                keyframe.post = parseAnimationVector(object.get("post"), defaultValue, context + ".post");
            }
            if (keyframe.pre == null && keyframe.post == null && isDirectVectorObject(object)) {
                keyframe.post = parseAnimationVector(object, defaultValue, context);
            }
            keyframe.interpolation = parseInterpolation(object.get("lerp_mode"),
                    object.get("interpolation"), context);
            if (keyframe.pre != null || keyframe.post != null) return keyframe;
        }
        keyframe.post = parseAnimationVector(element, defaultValue, context);
        return keyframe;
    }

    private static BbVectorValue parseAnimationVector(JsonElement element, float defaultValue,
                                                       String context) {
        if (element == null || element.isJsonNull()) {
            return scalarVector(BbScalarValue.number(defaultValue));
        }
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            return new BbVectorValue(
                    parseAnimationScalar(array.size() > 0 ? array.get(0) : null, defaultValue, context + "[0]"),
                    parseAnimationScalar(array.size() > 1 ? array.get(1) : null, defaultValue, context + "[1]"),
                    parseAnimationScalar(array.size() > 2 ? array.get(2) : null, defaultValue, context + "[2]"));
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("post")) return parseAnimationVector(object.get("post"), defaultValue, context + ".post");
            if (object.has("pre")) return parseAnimationVector(object.get("pre"), defaultValue, context + ".pre");
            if (object.has("vector")) return parseAnimationVector(object.get("vector"), defaultValue, context + ".vector");
            return new BbVectorValue(
                    parseAnimationScalar(object.get("x"), defaultValue, context + ".x"),
                    parseAnimationScalar(object.get("y"), defaultValue, context + ".y"),
                    parseAnimationScalar(object.get("z"), defaultValue, context + ".z"));
        }
        return scalarVector(parseAnimationScalar(element, defaultValue, context));
    }

    private static BbVectorValue scalarVector(BbScalarValue scalar) {
        return new BbVectorValue(scalar, scalar, scalar);
    }

    private static BbScalarValue parseAnimationScalar(JsonElement element, float defaultValue,
                                                       String context) {
        if (element == null || element.isJsonNull()) return BbScalarValue.number(defaultValue);
        if (!element.isJsonPrimitive()) {
            warnMolang(context + " 不支持的动态分量: " + shortValue(element));
            return BbScalarValue.number(defaultValue);
        }
        String value = element.getAsString().trim();
        if (value.isEmpty()) return BbScalarValue.number(defaultValue);
        try {
            float number = Float.parseFloat(value);
            if (Float.isFinite(number)) return BbScalarValue.number(number);
        } catch (NumberFormatException ignored) {
            // Preserve Molang source below.
        }
        return BbScalarValue.expression(value);
    }

    private static String parseInterpolation(JsonElement lerpMode, JsonElement interpolation,
                                             String context) {
        JsonElement element = lerpMode != null && !lerpMode.isJsonNull() ? lerpMode : interpolation;
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return "linear";
        String value = element.getAsString().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "linear", "step", "catmullrom", "smooth" -> value;
            default -> {
                warnMolang(context + " 不支持的插值模式: " + value + "，降级为 linear");
                yield "linear";
            }
        };
    }

    private static void putChannel(BbAnimator animator, String channel, float time,
                                   BbVectorKeyframe values) {
        switch (channel) {
            case "rotation" -> animator.rotation.put(time, values);
            case "position" -> animator.position.put(time, values);
            case "scale" -> animator.scale.put(time, values);
            default -> {
                // Filtered by isTransformChannel.
            }
        }
    }

    private static boolean parseBoolean(JsonElement element, boolean fallback) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            String value = primitive.getAsString().trim();
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) return true;
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) return false;
        } catch (RuntimeException ignored) {
            // Keep the caller-provided fallback.
        }
        return fallback;
    }

    private static String parseLoopMode(JsonElement element, String fallback) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return fallback;
        try {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean() ? "loop" : "once";
            String value = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            return switch (value) {
                case "true", "loop" -> "loop";
                case "false", "once" -> "once";
                case "hold", "hold_on_last_frame" -> "hold";
                default -> fallback;
            };
        } catch (RuntimeException ignored) {
            // Keep the previous/default loop mode.
        }
        return fallback;
    }

    private static final Set<String> MOLANG_WARNED = new HashSet<>();
    private static int molangCount;

    private static void warnMolang(String detail) {
        if (molangCount >= 21 || !MOLANG_WARNED.add(detail)) return;
        molangCount++;
        if (molangCount <= 20) {
            System.err.println("  [Molang 降级] " + detail);
        } else {
            System.err.println("  [Molang 降级] ... 更多动态表达式已省略");
        }
    }

    private static float[] floatArray(JsonObject obj, String key, int expectedLen) {
        if (!obj.has(key)) return new float[expectedLen];
        return fixedFloatArray(obj.get(key), expectedLen, 0f, key);
    }

    private static String safeString(JsonObject obj, String key, String def) {
        if (!obj.has(key)) return def;
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return def;
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return def;
        }
    }

    private static float[] fixedFloatArray(JsonElement element, int expectedLength,
                                           float defaultValue, String context) {
        float[] result = filledArray(expectedLength, defaultValue);
        if (element == null || element.isJsonNull()) return result;

        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            int count = Math.min(expectedLength, array.size());
            for (int i = 0; i < count; i++) {
                result[i] = parseFloat(array.get(i), defaultValue, context + "[" + i + "]");
            }
            return result;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("post")) {
                return fixedFloatArray(object.get("post"), expectedLength, defaultValue, context + ".post");
            }
            if (object.has("pre")) {
                return fixedFloatArray(object.get("pre"), expectedLength, defaultValue, context + ".pre");
            }
            if (object.has("vector")) {
                return fixedFloatArray(object.get("vector"), expectedLength, defaultValue, context + ".vector");
            }
            String[] axes = {"x", "y", "z", "w"};
            boolean foundAxis = false;
            for (int i = 0; i < expectedLength && i < axes.length; i++) {
                if (object.has(axes[i])) {
                    foundAxis = true;
                    result[i] = parseFloat(object.get(axes[i]), defaultValue,
                            context + "." + axes[i]);
                }
            }
            if (!foundAxis) {
                warnMolang(context + " 不支持的向量对象: " + shortValue(element));
            }
            return result;
        }

        float scalar = parseFloat(element, defaultValue, context);
        for (int i = 0; i < result.length; i++) result[i] = scalar;
        return result;
    }

    private static float[] filledArray(int length, float value) {
        float[] result = new float[length];
        if (value != 0f) {
            for (int i = 0; i < length; i++) result[i] = value;
        }
        return result;
    }

    private static float parseFloat(JsonElement element, float fallback, String context) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            warnMolang(context + ": " + shortValue(element));
            return fallback;
        }

        try {
            float value = Float.parseFloat(element.getAsString().trim());
            if (Float.isFinite(value)) return value;
        } catch (NumberFormatException | UnsupportedOperationException ignored) {
            // Molang and other dynamic expressions are represented as strings.
        }
        warnMolang(context + ": " + shortValue(element));
        return fallback;
    }

    private static String shortValue(JsonElement element) {
        if (element == null) return "null";
        String value = element.toString();
        return value.substring(0, Math.min(80, value.length()));
    }
}
