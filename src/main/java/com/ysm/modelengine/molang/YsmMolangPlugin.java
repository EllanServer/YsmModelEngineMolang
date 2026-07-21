package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.keyframe.data.IKeyframeData;
import com.ticxo.modelengine.api.animation.keyframe.data.KeyframeReaderRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Function;

public final class YsmMolangPlugin extends JavaPlugin {
    private EntityVariableStore variables;
    private RateLimitedDiagnostics diagnostics;
    private MolangCompilerFacade compiler;
    private MolangEvaluationContext evaluationContext;
    private MolangKeyframeReader reader;
    private KeyframeReaderRegistry keyframeReaders;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        long warningInterval = Math.max(0L,
                getConfig().getLong("diagnostics.unknown-query-warning-interval-seconds", 10L)) * 1000L;
        diagnostics = new RateLimitedDiagnostics(getLogger(), warningInterval);
        variables = new EntityVariableStore((float) getConfig().getDouble("variables.default-value", 0.0D));
        compiler = new MolangCompilerFacade(
                getConfig().getInt("diagnostics.max-cached-expressions", 4096), diagnostics);
        evaluationContext = new MolangEvaluationContext(variables, diagnostics, compiler.symbols());
        reader = new MolangKeyframeReader(compiler, evaluationContext);

        Plugin modelEngine = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (!(modelEngine instanceof ModelEngineAPI api)) {
            getLogger().severe("ModelEngine R4 API is not available; disabling YsmModelEngineMolang.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        keyframeReaders = api.getKeyframeReaderRegistry();
        Function<String, IKeyframeData> previous = keyframeReaders.get("molang");
        if (previous != null) {
            getLogger().warning("Replacing an existing ModelEngine 'molang' keyframe reader.");
        }
        keyframeReaders.register("molang", reader);
        getLogger().info("Registered ModelEngine molang keyframe reader.");

        if (getConfig().getBoolean("mythicmobs.register-mechanics", true)
                && Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            registerMythicIntegration();
        }
    }

    @Override
    public void onDisable() {
        if (evaluationContext != null) evaluationContext.clear();
        if (compiler != null) compiler.clear();
        if (variables != null) variables.clearAll();
        if (diagnostics != null) diagnostics.clear();
        getLogger().info("YsmModelEngineMolang disabled.");
    }

    public EntityVariableStore getVariables() {
        return variables;
    }

    public MolangCompilerFacade getCompiler() {
        return compiler;
    }

    private void registerMythicIntegration() {
        try {
            MythicMolangIntegration.register(this, variables);
        } catch (LinkageError | RuntimeException exception) {
            getLogger().warning("MythicMobs integration was not registered: " + exception.getMessage());
        }
    }
}
