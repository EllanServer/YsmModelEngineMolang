package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.animation.keyframe.data.IKeyframeData;
import gg.moonflower.molangcompiler.api.MolangExpression;

import java.util.function.Function;

final class MolangKeyframeReader implements Function<String, IKeyframeData> {
    private final MolangCompilerFacade compiler;
    private final MolangEvaluationContext context;

    MolangKeyframeReader(MolangCompilerFacade compiler, MolangEvaluationContext context) {
        this.compiler = compiler;
        this.context = context;
    }

    @Override
    public IKeyframeData apply(String source) {
        MolangExpression expression = compiler.compile(source);
        return new MolangKeyframeData(source, expression, context);
    }
}
