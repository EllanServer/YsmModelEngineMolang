package com.ysm.modelengine.molang;

import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.INoTargetSkill;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;

final class ClearMolangVariableMechanic implements INoTargetSkill, ITargetedEntitySkill {
    private final String key;
    private final EntityVariableStore variables;

    ClearMolangVariableMechanic(MythicLineConfig config, EntityVariableStore variables) {
        this.key = config.getString(new String[]{"key", "name", "variable"}, "");
        this.variables = variables;
    }

    @Override
    public SkillResult cast(SkillMetadata metadata) {
        return clear(metadata.getCaster().getEntity());
    }

    @Override
    public SkillResult castAtEntity(SkillMetadata metadata, AbstractEntity target) {
        return clear(target);
    }

    private SkillResult clear(AbstractEntity target) {
        if (target == null || target.getUniqueId() == null) return SkillResult.INVALID_TARGET;
        variables.clear(target.getUniqueId(), key);
        return SkillResult.SUCCESS;
    }
}
