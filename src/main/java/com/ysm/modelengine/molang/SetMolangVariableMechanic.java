package com.ysm.modelengine.molang;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.INoTargetSkill;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.placeholders.PlaceholderFloat;

final class SetMolangVariableMechanic implements INoTargetSkill, ITargetedEntitySkill {
    private final String key;
    private final PlaceholderFloat value;
    private final EntityVariableStore variables;

    SetMolangVariableMechanic(MythicLineConfig config, EntityVariableStore variables) {
        this.key = config.getString(new String[]{"key", "name", "variable"}, "");
        this.value = config.getPlaceholderFloat(new String[]{"value", "amount"}, 0.0F);
        this.variables = variables;
    }

    @Override
    public SkillResult cast(SkillMetadata metadata) {
        AbstractEntity caster = metadata.getCaster().getEntity();
        return set(caster, value.get(metadata));
    }

    @Override
    public SkillResult castAtEntity(SkillMetadata metadata, AbstractEntity target) {
        return set(target, value.get(metadata, target));
    }

    private SkillResult set(AbstractEntity target, float amount) {
        if (target == null || target.getUniqueId() == null || key.isBlank()) return SkillResult.INVALID_CONFIG;
        variables.set(target.getUniqueId(), key, amount);
        return SkillResult.SUCCESS;
    }
}
