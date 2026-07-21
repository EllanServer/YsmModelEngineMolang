package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.entity.BaseEntity;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import gg.moonflower.molangcompiler.api.MolangEnvironment;
import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;

final class MolangEvaluationContext {
    private final EntityVariableStore variables;
    private final RateLimitedDiagnostics diagnostics;

    MolangEvaluationContext(EntityVariableStore variables, RateLimitedDiagnostics diagnostics) {
        this.variables = variables;
        this.diagnostics = diagnostics;
    }

    float evaluate(IAnimationProperty property, MolangExpression expression) {
        MolangRuntime.Builder builder = MolangRuntime.runtime();
        UUID entityId = null;
        ActiveModel model = property == null ? null : property.getModel();
        ModeledEntity modeled = model == null ? null : model.getModeledEntity();
        BaseEntity<?> base = modeled == null ? null : modeled.getBase();
        Object original = base == null ? null : base.getOriginal();
        Entity entity = original instanceof Entity bukkitEntity ? bukkitEntity : null;

        float animationTime = property == null ? 0.0F : finite((float) property.getTime());
        float animationSpeed = property == null ? 1.0F : finite((float) property.getSpeed());
        float lifeTime = modeled == null ? 0.0F : modeled.getTick() / 20.0F;
        if (entity instanceof LivingEntity living) {
            entityId = living.getUniqueId();
            lifeTime = living.getTicksLived() / 20.0F;
        } else if (entity != null) {
            entityId = entity.getUniqueId();
        } else if (base != null) {
            entityId = base.getUUID();
        }

        float headYaw = modeled == null ? (base == null ? 0.0F : base.getYHeadRot()) : modeled.getYHeadRot();
        float headPitch = modeled == null ? (base == null ? 0.0F : base.getXHeadRot()) : modeled.getXHeadRot();
        float bodyYaw = modeled == null ? (base == null ? 0.0F : base.getYBodyRot()) : modeled.getYBodyRot();
        float health = base == null ? 0.0F : base.getHealth();
        float maxHealth = base == null ? 0.0F : base.getMaxHealth();
        boolean alive = base == null || base.isAlive();
        boolean onGround = base != null && base.isOnGround();
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

        setQuery(builder, "anim_time", animationTime);
        setQuery(builder, "delta_time", 0.05F * animationSpeed);
        setQuery(builder, "life_time", lifeTime);
        setQuery(builder, "head_y_rotation", headYaw);
        setQuery(builder, "head_x_rotation", headPitch);
        setQuery(builder, "body_y_rotation", bodyYaw);
        setQuery(builder, "is_alive", alive ? 1.0F : 0.0F);
        setQuery(builder, "is_on_ground", onGround ? 1.0F : 0.0F);
        setQuery(builder, "is_moving", moving ? 1.0F : 0.0F);
        setQuery(builder, "is_flying", flying ? 1.0F : 0.0F);
        setQuery(builder, "is_sneaking", sneaking ? 1.0F : 0.0F);
        setQuery(builder, "is_swimming", swimming ? 1.0F : 0.0F);
        setQuery(builder, "health", health);
        setQuery(builder, "max_health", maxHealth);
        setQuery(builder, "ground_speed", groundSpeed);
        setQuery(builder, "vertical_speed", verticalSpeed);
        setQuery(builder, "modified_distance_moved", groundSpeed);

        Map<String, Float> entityVariables = variables.snapshot(entityId);
        for (var entry : entityVariables.entrySet()) {
            builder.setVariable(entry.getKey(), MolangExpression.of(entry.getValue()));
        }

        MolangEnvironment environment = builder.create();
        return expression.get(environment);
    }

    void warn(String key, String message) {
        diagnostics.warn(key, message);
    }

    private static void setQuery(MolangRuntime.Builder builder, String name, float value) {
        builder.setQuery(name, MolangExpression.of(finite(value)));
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    static String normalizeVariableName(String name) {
        return EntityVariableStore.normalizeName(name);
    }
}
