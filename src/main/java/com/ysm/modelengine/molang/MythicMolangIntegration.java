package com.ysm.modelengine.molang;

import io.lumine.mythic.api.skills.ISkillMechanic;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

final class MythicMolangIntegration {
    private MythicMolangIntegration() {
    }

    static void register(JavaPlugin plugin, EntityVariableStore variables) {
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onMechanicLoad(MythicMechanicLoadEvent event) {
                String name = event.getMechanicName().toLowerCase();
                ISkillMechanic mechanic = switch (name) {
                    case "molangvar", "ysmvar" -> new SetMolangVariableMechanic(event.getConfig(), variables);
                    case "clearmolangvar" -> new ClearMolangVariableMechanic(event.getConfig(), variables);
                    default -> null;
                };
                if (mechanic != null) event.register(mechanic);
            }
        }, plugin);
        plugin.getLogger().info("Registered MythicMobs molangvar/clearmolangvar mechanics.");
    }
}
