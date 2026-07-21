package com.ysm.modelengine.molang;

import gg.moonflower.molangcompiler.api.MolangExpression;
import gg.moonflower.molangcompiler.api.MolangRuntime;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

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
    void rejectsInvalidExpression() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile("query."));
    }
}
