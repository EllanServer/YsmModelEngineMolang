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
    void rewritesLegacyYsmSyntaxWithoutChangingResults() throws Exception {
        var falseEnvironment = MolangRuntime.runtime()
                .setVariable("bag", MolangExpression.of(0.0F))
                .setVariable("qh", MolangExpression.of(0.0F))
                .setQuery("ground_speed", MolangExpression.of(0.0F))
                .setQuery("head_x_rotation", MolangExpression.of(18.0F))
                .create();
        var trueEnvironment = MolangRuntime.runtime()
                .setVariable("bag", MolangExpression.of(1.0F))
                .setVariable("qh", MolangExpression.of(1.0F))
                .setQuery("ground_speed", MolangExpression.of(1.0F))
                .setQuery("head_x_rotation", MolangExpression.of(18.0F))
                .create();

        assertEquals(1.0F, compiler.compile("!v.bag").get(falseEnvironment), 0.0001F);
        assertEquals(0.0F, compiler.compile("!v.bag").get(trueEnvironment), 0.0001F);
        assertEquals(0.0F, compiler.compile("v.qh?180").get(falseEnvironment), 0.0001F);
        assertEquals(180.0F, compiler.compile("v.qh?180").get(trueEnvironment), 0.0001F);
        assertEquals(-2.0F, compiler.compile("-(query.head_x_rotation/9)").get(trueEnvironment), 0.0001F);
        assertEquals(0.0F, compiler.compile("v.bv=q.ground_speed>0?5").get(falseEnvironment), 0.0001F);
        assertEquals(5.0F, compiler.compile("v.bv=q.ground_speed>0?5").get(trueEnvironment), 0.0001F);
        assertDoesNotThrow(() -> compiler.compile("7.-math.sin(160+q.anim_time*240)*0.5"));
    }

    @Test
    void rejectsInvalidExpression() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile("query."));
    }
}
