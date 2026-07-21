package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.animation.keyframe.data.IKeyframeData;
import com.ticxo.modelengine.api.animation.property.IAnimationProperty;
import gg.moonflower.molangcompiler.api.MolangExpression;

final class MolangKeyframeData implements IKeyframeData {
    private final String source;
    private final MolangExpression expression;
    private final MolangEvaluationContext context;

    MolangKeyframeData(String source, MolangExpression expression, MolangEvaluationContext context) {
        this.source = source;
        this.expression = expression;
        this.context = context;
    }

    @Override
    public double getValue(IAnimationProperty property) {
        try {
            float value = context.evaluate(property, expression);
            if (Float.isFinite(value)) return value;
            context.warn("nonfinite:" + source, "Molang expression returned a non-finite value: " + source);
        } catch (RuntimeException exception) {
            context.warn("runtime:" + source,
                    "Molang expression failed at runtime '" + source + "': " + exception.getMessage());
        }
        return 0.0D;
    }
}
