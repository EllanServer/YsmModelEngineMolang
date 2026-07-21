package com.ysm.modelengine.molang;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityVariableStoreTest {
    @Test
    void normalizesAliasesAndClearsValues() {
        EntityVariableStore store = new EntityVariableStore(-1.0F);
        UUID entityId = UUID.randomUUID();

        store.set(entityId, "Variable.Speed", 2.5F);
        store.set(entityId, "ctrl.attack", 1.0F);

        assertEquals(2.5F, store.get(entityId, "v.speed"));
        assertEquals(1.0F, store.get(entityId, "ctrl_attack"));
        assertEquals(2, store.snapshot(entityId).size());

        store.clear(entityId, "v.speed");
        assertEquals(-1.0F, store.get(entityId, "variable.speed"));

        store.clear(entityId, "");
        assertTrue(store.snapshot(entityId).isEmpty());
    }
}
