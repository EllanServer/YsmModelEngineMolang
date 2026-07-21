package com.ysm.modelengine.molang;

import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MolangCompilerFacadeTest {
    private final MolangCompilerFacade compiler = new MolangCompilerFacade(
            128,
            new RateLimitedDiagnostics(Logger.getAnonymousLogger(), Long.MAX_VALUE)
    );

    @Test
    void normalizesYsmAliases() {
        assertEquals(
                "query.head_y_rotation + query.head_x_rotation + query.body_y_rotation + v.ctrl_speed + v.offset",
                MolangCompilerFacade.normalize(
                        "molang:ysm.head_yaw + ysm.head_pitch + ysm.body_yaw + ctrl.speed + variable.offset"
                )
        );
    }

    @Test
    void compilesCachesAndEvaluatesDynamicQuery() throws Exception {
        MolangExpression first = compiler.compile("molang:query.anim_time * 90");
        MolangExpression second = compiler.compile("query.anim_time * 90");
        var environment = MolangRuntime.runtime()
                .setQuery("anim_time", MolangExpression.of(2.0F))
                .create();

        assertSame(first, second);
        assertEquals(180.0F, first.get(environment), 0.0001F);
        assertEquals(1, compiler.size());
    }

    @Test
    void evaluatesCaseInsensitiveYsmVariables() throws Exception {
        MolangExpression expression = compiler.compile("v.L12_P0");
        var environment = MolangRuntime.runtime()
                .setVariable("l12_p0", MolangExpression.of(7.0F))
                .create();

        assertEquals(7.0F, expression.get(environment), 0.0001F);
    }

    @Test
    void rewritesYsmStringArgumentExtensionsBeforeCompilation() {
        assertDoesNotThrow(() -> compiler.compile(
                "ysm.second_order('头发垂直', math.clamp(-3*q.vertical_speed+v.hv,-10,120), 2, 0.6, 0)"
        ));
        assertDoesNotThrow(() -> compiler.compile(
                "(v.legOrnamentsAngle - ysm.bone_rot('LeftLeg').x) * 0.5"
        ));
    }

    @Test
    void rejectsInvalidExpression() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile("query."));
    }
}
