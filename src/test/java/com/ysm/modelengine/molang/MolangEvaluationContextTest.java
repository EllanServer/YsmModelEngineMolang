package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import com.ticxo.modelengine.api.entity.BaseEntity;
import com.ticxo.modelengine.api.model.ActiveModel;
import com.ticxo.modelengine.api.model.ModeledEntity;
import gg.moonflower.molangcompiler.api.MolangExpression;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MolangEvaluationContextTest {
    @Test
    void evaluatesTimeVariablesAndYsmExtensionsOnEachFrame() {
        UUID entityId = UUID.randomUUID();
        AtomicReference<Float> animationTime = new AtomicReference<>(0.0F);
        AtomicInteger tick = new AtomicInteger(0);
        Player player = proxy(Player.class, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> entityId;
            case "getTicksLived" -> tick.get();
            case "getFoodLevel" -> 17;
            case "isSneaking", "isSwimming", "isOnGround" -> false;
            case "getVelocity" -> new Vector(0, 0, 0);
            case "getEquipment" -> null;
            default -> defaultValue(method.getReturnType());
        });
        BaseEntity<?> base = proxy(BaseEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getOriginal" -> player;
            case "getUUID" -> entityId;
            case "isAlive" -> true;
            case "getYHeadRot", "getXHeadRot", "getYBodyRot" -> 0.0F;
            case "getHealth", "getMaxHealth" -> 20.0F;
            case "isWalking", "isFlying" -> false;
            default -> defaultValue(method.getReturnType());
        });
        ModeledEntity modeled = proxy(ModeledEntity.class, (proxy, method, args) -> switch (method.getName()) {
            case "getBase" -> base;
            case "getTick" -> tick.get();
            case "getYHeadRot", "getXHeadRot", "getYBodyRot" -> 0.0F;
            default -> defaultValue(method.getReturnType());
        });
        ActiveModel model = proxy(ActiveModel.class, (proxy, method, args) -> switch (method.getName()) {
            case "getModeledEntity" -> modeled;
            case "getBone" -> Optional.empty();
            default -> defaultValue(method.getReturnType());
        });
        IAnimationProperty property = proxy(IAnimationProperty.class, (proxy, method, args) -> switch (method.getName()) {
            case "getModel" -> model;
            case "getTime" -> animationTime.get().doubleValue();
            case "getSpeed" -> 1.0D;
            default -> defaultValue(method.getReturnType());
        });

        EntityVariableStore variables = new EntityVariableStore(0.0F);
        variables.set(entityId, "L12_P0", 2.0F);
        RateLimitedDiagnostics diagnostics = new RateLimitedDiagnostics(
                Logger.getAnonymousLogger(), Long.MAX_VALUE);
        MolangCompilerFacade compiler = new MolangCompilerFacade(128, diagnostics);
        MolangEvaluationContext context = new MolangEvaluationContext(
                variables, diagnostics, compiler.symbols());

        MolangExpression animated = compiler.compile("math.sin(query.anim_time*90)+v.L12_P0");
        assertEquals(2.0F, context.evaluate(property, animated), 0.0001F);

        animationTime.set(1.0F);
        tick.set(20);
        assertEquals(3.0F, context.evaluate(property, animated), 0.0001F);

        assertEquals(17.0F, context.evaluate(property, compiler.compile("ysm.food_level")), 0.0001F);
        assertEquals(0.0F, context.evaluate(property, compiler.compile("ysm.has_mainhand?30:0")), 0.0001F);
        assertEquals(2.0F, context.evaluate(property,
                compiler.compile("(v.L12_P0-ysm.bone_rot('LeftLeg').x)*1")), 0.0001F);

        context.clear();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if (type == Optional.class) return Optional.empty();
            if (type == Map.class) return Map.of();
            if (type == Set.class) return Set.of();
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }
}
