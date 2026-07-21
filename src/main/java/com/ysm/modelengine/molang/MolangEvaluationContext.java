package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.entity.BaseEntity;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import com.ticxo.modelengine.api.model.bone.ModelBone;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import gg.moonflower.molangcompiler.api.exception.MolangRuntimeException;
import gg.moonflower.molangcompiler.api.object.MolangObject;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class MolangEvaluationContext {
    private final EntityVariableStore variables;
    private final RateLimitedDiagnostics diagnostics;
    private final MolangSymbolTable symbols;
    private final Map<SecondOrderKey, SecondOrderState> secondOrderStates = new ConcurrentHashMap<>();
    private final Map<UUID, YawState> yawStates = new ConcurrentHashMap<>();

    MolangEvaluationContext(EntityVariableStore variables, RateLimitedDiagnostics diagnostics) {
        this(variables, diagnostics, new MolangSymbolTable());
    }

    MolangEvaluationContext(EntityVariableStore variables, RateLimitedDiagnostics diagnostics,
                            MolangSymbolTable symbols) {
        this.variables = variables;
        this.diagnostics = diagnostics;
        this.symbols = symbols;
    }

    float evaluate(IAnimationProperty property, MolangExpression expression) {
        Frame frame = capture(property);
        MolangRuntime.Builder builder = MolangRuntime.runtime();

        setQuery(builder, "anim_time", frame.animationTime);
        setQuery(builder, "delta_time", 0.05F * frame.animationSpeed);
        setQuery(builder, "life_time", frame.lifeTime);
        setQuery(builder, "head_y_rotation", frame.headYaw);
        setQuery(builder, "head_x_rotation", frame.headPitch);
        setQuery(builder, "body_y_rotation", frame.bodyYaw);
        setQuery(builder, "is_alive", frame.alive ? 1.0F : 0.0F);
        setQuery(builder, "is_on_ground", frame.onGround ? 1.0F : 0.0F);
        setQuery(builder, "is_moving", frame.moving ? 1.0F : 0.0F);
        setQuery(builder, "is_flying", frame.flying ? 1.0F : 0.0F);
        setQuery(builder, "is_sneaking", frame.sneaking ? 1.0F : 0.0F);
        setQuery(builder, "is_swimming", frame.swimming ? 1.0F : 0.0F);
        setQuery(builder, "health", frame.health);
        setQuery(builder, "max_health", frame.maxHealth);
        setQuery(builder, "ground_speed", frame.groundSpeed);
        setQuery(builder, "vertical_speed", frame.verticalSpeed);
        setQuery(builder, "modified_distance_moved", frame.groundSpeed);
        setQuery(builder, "yaw_speed", yawSpeed(frame));

        for (var entry : variables.snapshot(frame.entityId).entrySet()) {
            builder.setVariable(entry.getKey(), MolangExpression.of(entry.getValue()));
        }
        builder.loadLibrary("ysm", new YsmLibrary(frame));

        MolangEnvironment environment = builder.create();
        try {
            return expression.get(environment);
        } catch (MolangRuntimeException exception) {
            throw new IllegalStateException("Molang runtime evaluation failed", exception);
        }
    }

    void clear() {
        secondOrderStates.clear();
        yawStates.clear();
    }

    void warn(String key, String message) {
        diagnostics.warn(key, message);
    }

    private Frame capture(IAnimationProperty property) {
        ActiveModel model = property == null ? null : property.getModel();
        ModeledEntity modeled = model == null ? null : model.getModeledEntity();
        BaseEntity<?> base = modeled == null ? null : modeled.getBase();
        Object original = base == null ? null : base.getOriginal();
        Entity entity = original instanceof Entity bukkitEntity ? bukkitEntity : null;

        UUID entityId = null;
        float lifeTime = modeled == null ? 0.0F : modeled.getTick() / 20.0F;
        if (entity instanceof LivingEntity living) {
            entityId = living.getUniqueId();
            lifeTime = living.getTicksLived() / 20.0F;
        } else if (entity != null) {
            entityId = entity.getUniqueId();
        } else if (base != null) {
            entityId = base.getUUID();
        }

        float animationTime = property == null ? 0.0F : finite((float) property.getTime());
        float animationSpeed = property == null ? 1.0F : finite((float) property.getSpeed());
        float headYaw = modeled == null ? (base == null ? 0.0F : base.getYHeadRot()) : modeled.getYHeadRot();
        float headPitch = modeled == null ? (base == null ? 0.0F : base.getXHeadRot()) : modeled.getXHeadRot();
        float bodyYaw = modeled == null ? (base == null ? 0.0F : base.getYBodyRot()) : modeled.getYBodyRot();
        float health = base == null ? 0.0F : base.getHealth();
        float maxHealth = base == null ? 0.0F : base.getMaxHealth();
        boolean alive = base == null || base.isAlive();
        boolean onGround = entity != null && entity.isOnGround();
        boolean moving = base != null && base.isWalking();
        boolean flying = base != null && base.isFlying();
        boolean sneaking = entity instanceof LivingEntity living && living.isSneaking();
        boolean swimming = entity instanceof LivingEntity living && living.isSwimming();
        float groundSpeed = 0.0F;
        float verticalSpeed = 0.0F;
        if (entity != null) {
            var velocity = entity.getVelocity();
            groundSpeed = (float) Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
            verticalSpeed = (float) velocity.getY();
        }

        boolean hasMainHand = false;
        boolean hasOffHand = false;
        int foodLevel = 20;
        if (entity instanceof LivingEntity living) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                hasMainHand = hasItem(equipment.getItemInMainHand());
                hasOffHand = hasItem(equipment.getItemInOffHand());
            }
            if (living instanceof Player player) foodLevel = player.getFoodLevel();
        }

        int tick = modeled == null
                ? Math.round(animationTime * 20.0F)
                : modeled.getTick();
        return new Frame(
                property, model, modeled, base, entity, entityId, tick,
                animationTime, animationSpeed, finite(lifeTime), finite(headYaw), finite(headPitch), finite(bodyYaw),
                finite(health), finite(maxHealth), alive, onGround, moving, flying, sneaking, swimming,
                finite(groundSpeed), finite(verticalSpeed), hasMainHand, hasOffHand, foodLevel
        );
    }

    private static boolean hasItem(ItemStack item) {
        return item != null && !item.getType().isAir();
    }

    private float yawSpeed(Frame frame) {
        if (frame.entityId == null) return 0.0F;
        YawState state = yawStates.computeIfAbsent(frame.entityId, ignored -> new YawState());
        synchronized (state) {
            if (state.tick == frame.tick) return state.speed;
            float speed = 0.0F;
            if (state.tick >= 0) {
                float delta = wrapDegrees(frame.headYaw - state.yaw);
                float deltaSeconds = Math.max(0.05F, (frame.tick - state.tick) * 0.05F);
                speed = delta / deltaSeconds;
            }
            state.tick = frame.tick;
            state.yaw = frame.headYaw;
            state.speed = finite(speed);
            return state.speed;
        }
    }

    private float secondOrder(Frame frame, int symbolId, float target, float frequency,
                              float damping, float response) {
        target = finite(target);
        if (frame.entityId == null) return target;

        SecondOrderKey key = new SecondOrderKey(frame.entityId, symbolId);
        SecondOrderState state = secondOrderStates.computeIfAbsent(key, ignored -> new SecondOrderState());
        synchronized (state) {
            if (state.tick == frame.tick) return finite(state.value);
            if (state.tick < 0) {
                state.tick = frame.tick;
                state.value = target;
                state.velocity = 0.0F;
                return target;
            }

            float dt = Math.max(0.001F, (frame.tick - state.tick) * 0.05F);
            float omega = Math.max(0.01F, Math.abs(finite(frequency)));
            float dampingRatio = Math.max(0.0F, finite(damping));
            float responseOffset = finite(response);
            float desired = target + responseOffset;
            float acceleration = omega * omega * (desired - state.value)
                    - 2.0F * dampingRatio * omega * state.velocity;
            state.velocity = finite(state.velocity + acceleration * dt);
            state.value = finite(state.value + state.velocity * dt);
            state.tick = frame.tick;
            return state.value;
        }
    }

    private float boneRotation(Frame frame, int symbolId, int axis) {
        String boneName = symbols.nameFor(symbolId);
        if (boneName == null || frame.model == null) return 0.0F;
        try {
            ModelBone bone = frame.model.getBone(boneName).orElse(null);
            if (bone == null || bone.getLocalTransform() == null || bone.getLocalTransform().getLeftEuler() == null) {
                return 0.0F;
            }
            var euler = bone.getLocalTransform().getLeftEuler();
            float radians = switch (axis) {
                case 0 -> euler.x();
                case 1 -> euler.y();
                case 2 -> euler.z();
                default -> 0.0F;
            };
            return finite((float) Math.toDegrees(radians));
        } catch (RuntimeException exception) {
            diagnostics.warn("bone:" + boneName, "Unable to read ModelEngine bone rotation: " + boneName);
            return 0.0F;
        }
    }

    private static void setQuery(MolangRuntime.Builder builder, String name, float value) {
        builder.setQuery(name, MolangExpression.of(finite(value)));
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped > 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    static String normalizeVariableName(String name) {
        return EntityVariableStore.normalizeName(name);
    }

    private final class YsmLibrary implements MolangObject {
        private final Map<String, MolangExpression> values;

        private YsmLibrary(Frame frame) {
            Map<String, MolangExpression> entries = new LinkedHashMap<>();
            entries.put("has_mainhand", MolangExpression.of(frame.hasMainHand ? 1.0F : 0.0F));
            entries.put("has_offhand", MolangExpression.of(frame.hasOffHand ? 1.0F : 0.0F));
            entries.put("food_level", MolangExpression.of(frame.foodLevel));
            entries.put("second_order", MolangExpression.function(5, context -> secondOrder(
                    frame, Math.round(context.get(0)), context.get(1), context.get(2), context.get(3), context.get(4))));
            entries.put("bone_rot_x", MolangExpression.function(1,
                    context -> boneRotation(frame, Math.round(context.get(0)), 0)));
            entries.put("bone_rot_y", MolangExpression.function(1,
                    context -> boneRotation(frame, Math.round(context.get(0)), 1)));
            entries.put("bone_rot_z", MolangExpression.function(1,
                    context -> boneRotation(frame, Math.round(context.get(0)), 2)));
            values = Map.copyOf(entries);
        }

        @Override
        public MolangExpression get(String name) throws MolangRuntimeException {
            MolangExpression expression = values.get(name);
            if (expression == null) throw new MolangRuntimeException("Unknown YSM Molang value: " + name);
            return expression;
        }

        @Override
        public void set(String name, MolangExpression value) throws MolangRuntimeException {
            throw new MolangRuntimeException("YSM Molang values are immutable");
        }

        @Override
        public void remove(String name) throws MolangRuntimeException {
            throw new MolangRuntimeException("YSM Molang values are immutable");
        }

        @Override
        public boolean has(String name) {
            return values.containsKey(name);
        }

        @Override
        public Collection<String> getKeys() {
            return values.keySet();
        }

        @Override
        public boolean isMutable() {
            return false;
        }
    }

    private record Frame(
            IAnimationProperty property,
            ActiveModel model,
            ModeledEntity modeled,
            BaseEntity<?> base,
            Entity entity,
            UUID entityId,
            int tick,
            float animationTime,
            float animationSpeed,
            float lifeTime,
            float headYaw,
            float headPitch,
            float bodyYaw,
            float health,
            float maxHealth,
            boolean alive,
            boolean onGround,
            boolean moving,
            boolean flying,
            boolean sneaking,
            boolean swimming,
            float groundSpeed,
            float verticalSpeed,
            boolean hasMainHand,
            boolean hasOffHand,
            int foodLevel
    ) {
    }

    private record SecondOrderKey(UUID entityId, int symbolId) {
    }

    private static final class SecondOrderState {
        private int tick = -1;
        private float value;
        private float velocity;
    }

    private static final class YawState {
        private int tick = -1;
        private float yaw;
        private float speed;
    }
}
