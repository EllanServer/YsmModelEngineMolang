package com.ysm.converter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory representation of a Blockbench .bbmodel project.
 *
 * <p>The writer intentionally targets the legacy 4.12 project layout. Current
 * Blockbench versions migrate this layout on load, while older releases can
 * still open it directly. Elements are referenced from the outliner by UUID;
 * embedding element objects in group children makes the project appear empty.
 */
final class BbModel {

    final JsonObject meta = new JsonObject();
    final List<JsonObject> textures = new ArrayList<>();
    final List<BbGroup> outliner = new ArrayList<>();
    final List<BbAnimation> animations = new ArrayList<>();
    final Map<String, BbGroup> groupMap = new LinkedHashMap<>();
    final Map<String, BbAnimation> animMap = new LinkedHashMap<>();

    private final Map<String, Integer> textureIndexByPath = new LinkedHashMap<>();
    private String name = "ysm_model";
    private int textureWidth = 16;
    private int textureHeight = 16;

    BbModel() {
        meta.addProperty("format_version", "4.12");
        meta.addProperty("model_format", "free");
        meta.addProperty("box_uv", false);
    }

    void setName(String name) {
        if (name != null && !name.isBlank()) this.name = name;
    }

    void setResolution(float width, float height) {
        if (Float.isFinite(width) && width > 0) {
            textureWidth = Math.max(textureWidth, Math.round(width));
        }
        if (Float.isFinite(height) && height > 0) {
            textureHeight = Math.max(textureHeight, Math.round(height));
        }
    }

    int addTexture(Path texturePath) throws IOException {
        Path absolute = texturePath.toAbsolutePath().normalize();
        String normalizedPath = normalizePath(absolute.toString());
        Integer existing = textureIndexByPath.get(normalizedPath);
        if (existing != null) return existing;

        int index = textures.size();
        String fileName = absolute.getFileName().toString();
        byte[] bytes = Files.readAllBytes(absolute);

        JsonObject texture = new JsonObject();
        texture.addProperty("path", absolute.toString());
        texture.addProperty("name", fileName);
        texture.addProperty("folder", "");
        texture.addProperty("namespace", "");
        texture.addProperty("id", Integer.toString(index));
        texture.addProperty("particle", false);
        texture.addProperty("render_mode", "default");
        texture.addProperty("visible", true);
        texture.addProperty("mode", "bitmap");
        texture.addProperty("saved", true);
        texture.addProperty("uuid", UUID.randomUUID().toString());
        if (bytes.length > 0) {
            texture.addProperty("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes));
        }
        textures.add(texture);

        textureIndexByPath.put(normalizedPath, index);
        textureIndexByPath.putIfAbsent(normalizePath(fileName), index);

        try {
            BufferedImage image = ImageIO.read(absolute.toFile());
            if (image != null) setResolution(image.getWidth(), image.getHeight());
        } catch (RuntimeException ignored) {
            // A missing/invalid preview texture must not prevent geometry loading.
        }
        return index;
    }

    int findTextureIndex(String pathOrName) {
        if (textures.isEmpty()) return -1;
        if (pathOrName == null || pathOrName.isBlank()) return 0;

        String normalized = normalizePath(pathOrName);
        Integer direct = textureIndexByPath.get(normalized);
        if (direct != null) return direct;

        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        direct = textureIndexByPath.get(fileName);
        if (direct != null) return direct;

        for (var entry : textureIndexByPath.entrySet()) {
            if (entry.getKey().endsWith('/' + normalized) || entry.getKey().endsWith('/' + fileName)) {
                return entry.getValue();
            }
        }
        return 0;
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    BbGroup getOrCreateGroup(String name, String parentName) {
        BbGroup group = groupMap.get(name);
        if (group == null) {
            group = new BbGroup();
            group.name = name;
            group.color = groupMap.size() % 8;
            groupMap.put(name, group);
            outliner.add(group);
        }

        if (parentName != null && !parentName.isBlank() && !parentName.equals(name)) {
            BbGroup parent = getOrCreateGroup(parentName, null);
            if (group.parent != parent) {
                if (group.parent != null) group.parent.children.remove(group);
                outliner.remove(group);
                group.parent = parent;
                if (!parent.children.contains(group)) parent.children.add(group);
            }
        }
        return group;
    }

    /**
     * Assign deterministic render priorities to flat elements that actually
     * overlap on the same plane. A simple cyclic assignment is insufficient:
     * two non-adjacent elements can still share a layer while their faces
     * overlap, and three.js then has no stable priority to use.
     */
    void assignFlatLayerRenderPriorities(float maxThickness) {
        final String[] layeredOrders = {"behind", "default", "in_front"};
        final float planeEpsilon = Math.max(maxThickness, 0.00001f);
        final List<BbElement> flatElements = new ArrayList<>();
        final Map<BbElement, List<FlatFace>> facesByElement = new LinkedHashMap<>();

        for (BbGroup group : groupMap.values()) {
            for (BbElement element : group.elements) {
                element.renderOrder = "default";
                List<FlatFace> faces = flatFaces(element, maxThickness);
                if (!faces.isEmpty()) {
                    flatElements.add(element);
                    facesByElement.put(element, faces);
                }
            }
        }
        if (flatElements.isEmpty()) return;

        Map<BbElement, Set<BbElement>> conflicts = new LinkedHashMap<>();
        for (BbElement element : flatElements) conflicts.put(element, new HashSet<>());
        for (int i = 0; i < flatElements.size(); i++) {
            BbElement first = flatElements.get(i);
            for (int j = i + 1; j < flatElements.size(); j++) {
                BbElement second = flatElements.get(j);
                if (!hasCoplanarOverlap(facesByElement.get(first), facesByElement.get(second), planeEpsilon)) {
                    continue;
                }
                conflicts.get(first).add(second);
                conflicts.get(second).add(first);
            }
        }

        Map<BbElement, Integer> sourceOrder = new HashMap<>();
        for (int i = 0; i < flatElements.size(); i++) sourceOrder.put(flatElements.get(i), i);
        List<BbElement> coloringOrder = new ArrayList<>(flatElements);
        coloringOrder.sort(Comparator
                .comparingInt((BbElement element) -> conflicts.get(element).size())
                .reversed()
                .thenComparingInt(sourceOrder::get));

        Map<BbElement, Integer> colors = new HashMap<>();
        for (BbElement element : coloringOrder) {
            boolean[] used = new boolean[layeredOrders.length];
            for (BbElement neighbor : conflicts.get(element)) {
                Integer color = colors.get(neighbor);
                if (color != null && color >= 0 && color < used.length) used[color] = true;
            }
            int selected = 0;
            while (selected < used.length && used[selected]) selected++;
            // The current YSM models fit in three layers. If a future model
            // contains a larger coplanar clique, keep a deterministic fallback
            // rather than reintroducing the old cyclic assignment.
            if (selected >= layeredOrders.length) selected = sourceOrder.get(element) % layeredOrders.length;
            colors.put(element, selected);
            element.renderOrder = layeredOrders[selected];
        }
    }

    private static List<FlatFace> flatFaces(BbElement element, float maxThickness) {
        List<FlatFace> faces = new ArrayList<>();
        float sizeX = Math.abs(element.to[0] - element.from[0]);
        float sizeY = Math.abs(element.to[1] - element.from[1]);
        float sizeZ = Math.abs(element.to[2] - element.from[2]);
        if (sizeX <= maxThickness) {
            addFlatFace(faces, element, "west", 'x', element.from[0], 1, 2);
            addFlatFace(faces, element, "east", 'x', element.to[0], 1, 2);
        }
        if (sizeY <= maxThickness) {
            addFlatFace(faces, element, "down", 'y', element.from[1], 0, 2);
            addFlatFace(faces, element, "up", 'y', element.to[1], 0, 2);
        }
        if (sizeZ <= maxThickness) {
            addFlatFace(faces, element, "north", 'z', element.from[2], 0, 1);
            addFlatFace(faces, element, "south", 'z', element.to[2], 0, 1);
        }
        return faces;
    }

    private static void addFlatFace(List<FlatFace> faces, BbElement element, String faceName,
                                    char axis, float plane, int firstAxis, int secondAxis) {
        BbFace face = switch (faceName) {
            case "north" -> element.faces.north;
            case "south" -> element.faces.south;
            case "east" -> element.faces.east;
            case "west" -> element.faces.west;
            case "up" -> element.faces.up;
            case "down" -> element.faces.down;
            default -> null;
        };
        if (face == null || face.texture < 0) return;
        float firstMin = Math.min(element.from[firstAxis], element.to[firstAxis]);
        float firstMax = Math.max(element.from[firstAxis], element.to[firstAxis]);
        float secondMin = Math.min(element.from[secondAxis], element.to[secondAxis]);
        float secondMax = Math.max(element.from[secondAxis], element.to[secondAxis]);
        if (firstMax - firstMin <= 0.000001f || secondMax - secondMin <= 0.000001f) return;
        faces.add(new FlatFace(axis, plane, firstMin, firstMax, secondMin, secondMax));
    }

    private static boolean hasCoplanarOverlap(List<FlatFace> firstFaces, List<FlatFace> secondFaces,
                                               float planeEpsilon) {
        for (FlatFace first : firstFaces) {
            for (FlatFace second : secondFaces) {
                if (first.axis != second.axis || Math.abs(first.plane - second.plane) > planeEpsilon) continue;
                float firstOverlap = Math.min(first.maxA, second.maxA) - Math.max(first.minA, second.minA);
                float secondOverlap = Math.min(first.maxB, second.maxB) - Math.max(first.minB, second.minB);
                if (firstOverlap > 0.000001f && secondOverlap > 0.000001f) return true;
            }
        }
        return false;
    }

    private record FlatFace(char axis, float plane, float minA, float maxA, float minB, float maxB) {
    }

    BbAnimation getOrCreateAnimation(String name) {
        return animMap.computeIfAbsent(name, key -> {
            BbAnimation animation = new BbAnimation();
            animation.name = name;
            animations.add(animation);
            return animation;
        });
    }

    void writeTo(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        var gson = new GsonBuilder().setPrettyPrinting().create();
        JsonObject root = new JsonObject();
        root.add("meta", meta);
        root.addProperty("name", name);
        root.addProperty("variable_placeholders", "");
        root.add("variable_placeholder_buttons", new JsonArray());
        root.add("timeline_setups", new JsonArray());
        root.add("unhandled_root_fields", new JsonObject());

        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", textureWidth);
        resolution.addProperty("height", textureHeight);
        root.add("resolution", resolution);

        JsonArray elements = new JsonArray();
        Set<String> visitedGroups = new HashSet<>();
        for (BbGroup group : outliner) flattenGroups(group, elements, visitedGroups);
        root.add("elements", elements);

        JsonArray outlinerArray = new JsonArray();
        Set<String> writtenGroups = new HashSet<>();
        for (BbGroup group : outliner) {
            JsonObject groupJson = group.toLegacyOutlinerJson(writtenGroups);
            if (groupJson != null) outlinerArray.add(groupJson);
        }
        root.add("outliner", outlinerArray);

        JsonArray textureArray = new JsonArray();
        for (JsonObject texture : textures) textureArray.add(texture);
        root.add("textures", textureArray);

        JsonArray animationArray = new JsonArray();
        for (BbAnimation animation : animations) {
            JsonObject animationJson = animation.toJson(groupMap);
            if (animationJson != null) animationArray.add(animationJson);
        }
        root.add("animations", animationArray);

        Files.writeString(path, gson.toJson(root), StandardCharsets.UTF_8);
    }

    private void flattenGroups(BbGroup group, JsonArray elements, Set<String> visitedGroups) {
        if (!visitedGroups.add(group.uuid)) return;
        for (BbElement element : group.elements) elements.add(element.toJson());
        for (BbGroup child : group.children) flattenGroups(child, elements, visitedGroups);
    }

    static JsonArray floatArray(float[] values) {
        JsonArray array = new JsonArray();
        for (float value : values) array.add(value);
        return array;
    }

    static boolean hasNonZero(float[] values) {
        for (float value : values) {
            if (Math.abs(value) > 0.000001f) return true;
        }
        return false;
    }
}

final class BbGroup {
    final String uuid = UUID.randomUUID().toString();
    String name;
    int color;
    float[] origin = {0, 0, 0};
    float[] rotation = {0, 0, 0};
    boolean visible = true;
    BbGroup parent;
    final List<BbElement> elements = new ArrayList<>();
    final List<BbGroup> children = new ArrayList<>();

    JsonObject toLegacyOutlinerJson(Set<String> visited) {
        if (!visited.add(uuid)) return null;

        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.add("origin", BbModel.floatArray(origin));
        if (BbModel.hasNonZero(rotation)) object.add("rotation", BbModel.floatArray(rotation));
        object.addProperty("color", color);
        object.addProperty("uuid", uuid);
        object.addProperty("export", true);
        object.addProperty("isOpen", false);
        if (!visible) object.addProperty("visibility", false);

        JsonArray childArray = new JsonArray();
        for (BbElement element : elements) childArray.add(element.uuid);
        for (BbGroup child : children) {
            JsonObject childJson = child.toLegacyOutlinerJson(visited);
            if (childJson != null) childArray.add(childJson);
        }
        object.add("children", childArray);
        return object;
    }
}

final class BbElement {
    final String uuid = UUID.randomUUID().toString();
    String name;
    float[] from = {0, 0, 0};
    float[] to = {0, 0, 0};
    float[] origin = {0, 0, 0};
    float[] rotation = {0, 0, 0};
    float inflate;
    boolean mirrorUv;
    String renderOrder = "default";
    int color;
    BbFaces faces = new BbFaces();

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("type", "cube");
        object.add("from", BbModel.floatArray(from));
        object.add("to", BbModel.floatArray(to));
        object.addProperty("autouv", 0);
        object.addProperty("color", color);
        object.add("origin", BbModel.floatArray(origin));
        if (BbModel.hasNonZero(rotation)) object.add("rotation", BbModel.floatArray(rotation));
        if (inflate != 0f) object.addProperty("inflate", inflate);
        if (mirrorUv) object.addProperty("mirror_uv", true);
        if (!"default".equals(renderOrder)) object.addProperty("render_order", renderOrder);
        object.add("faces", faces.toJson());
        object.addProperty("uuid", uuid);
        return object;
    }
}

final class BbFaces {
    BbFace north, south, east, west, up, down;

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        if (north != null) object.add("north", north.toJson());
        if (south != null) object.add("south", south.toJson());
        if (east != null) object.add("east", east.toJson());
        if (west != null) object.add("west", west.toJson());
        if (up != null) object.add("up", up.toJson());
        if (down != null) object.add("down", down.toJson());
        return object;
    }
}

final class BbFace {
    float[] uv = {0, 0, 0, 0};
    int texture = -1;

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.add("uv", BbModel.floatArray(uv));
        if (texture >= 0) object.addProperty("texture", texture);
        else object.add("texture", JsonNull.INSTANCE);
        return object;
    }
}

final class BbAnimation {
    String name;
    float length = 1.0f;
    String loopMode = "loop";
    String animTimeUpdate = "";
    final Map<String, BbAnimator> animators = new LinkedHashMap<>();

    BbAnimator getOrCreateAnimator(String boneName) {
        return animators.computeIfAbsent(boneName, key -> {
            BbAnimator animator = new BbAnimator();
            animator.name = boneName;
            return animator;
        });
    }

    JsonObject toJson(Map<String, BbGroup> groups) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("loop", loopMode);
        object.addProperty("override", false);
        object.addProperty("length", length);
        object.addProperty("snapping", 24);
        object.addProperty("selected", false);
        object.addProperty("anim_time_update", animTimeUpdate);
        object.addProperty("blend_weight", "");
        object.addProperty("start_delay", "");
        object.addProperty("loop_delay", "");

        JsonObject animatorObject = new JsonObject();
        for (var entry : animators.entrySet()) {
            BbGroup group = groups.get(entry.getKey());
            if (group == null) continue;
            animatorObject.add(group.uuid, entry.getValue().toJson());
        }
        if (animatorObject.size() == 0) return null;
        object.add("animators", animatorObject);
        return object;
    }
}

final class BbAnimator {
    String name;
    String type = "bone";
    final Map<Float, BbVectorKeyframe> rotation = new LinkedHashMap<>();
    final Map<Float, BbVectorKeyframe> position = new LinkedHashMap<>();
    final Map<Float, BbVectorKeyframe> scale = new LinkedHashMap<>();

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("type", type);

        JsonArray keyframes = new JsonArray();
        addChannel(keyframes, "rotation", rotation);
        addChannel(keyframes, "position", position);
        addChannel(keyframes, "scale", scale);
        object.add("keyframes", keyframes);
        return object;
    }

    private void addChannel(JsonArray output, String channel, Map<Float, BbVectorKeyframe> values) {
        for (var entry : values.entrySet()) {
            BbVectorKeyframe value = entry.getValue();
            JsonObject keyframe = new JsonObject();
            keyframe.addProperty("channel", channel);
            keyframe.addProperty("time", entry.getKey());
            keyframe.addProperty("interpolation", value.interpolation);
            keyframe.addProperty("uuid", UUID.randomUUID().toString());
            keyframe.add("data_points", value.toDataPoints());
            output.add(keyframe);
        }
    }
}

final class BbVectorKeyframe {
    BbVectorValue pre;
    BbVectorValue post;
    String interpolation = "linear";

    JsonArray toDataPoints() {
        JsonArray points = new JsonArray();
        if (pre != null) points.add(pre.toJson());
        if (post != null && post != pre) points.add(post.toJson());
        if (points.size() == 0) points.add(BbVectorValue.zero().toJson());
        return points;
    }
}

final class BbVectorValue {
    final BbScalarValue x;
    final BbScalarValue y;
    final BbScalarValue z;

    BbVectorValue(BbScalarValue x, BbScalarValue y, BbScalarValue z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    static BbVectorValue zero() {
        return new BbVectorValue(BbScalarValue.number(0f), BbScalarValue.number(0f), BbScalarValue.number(0f));
    }

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.add("x", x.toJson());
        object.add("y", y.toJson());
        object.add("z", z.toJson());
        return object;
    }
}

final class BbScalarValue {
    private final Float number;
    private final String expression;

    private BbScalarValue(Float number, String expression) {
        this.number = number;
        this.expression = expression;
    }

    static BbScalarValue number(float value) {
        return new BbScalarValue(value, null);
    }

    static BbScalarValue expression(String value) {
        return new BbScalarValue(null, value);
    }

    JsonElement toJson() {
        return expression == null ? new com.google.gson.JsonPrimitive(number) :
                new com.google.gson.JsonPrimitive("molang:" + expression);
    }
}
