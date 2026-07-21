package com.ysm.modelengine.molang;

import com.ticxo.modelengine.api.ModelEngineAPI;
import com.ticxo.modelengine.api.animation.keyframe.data.IKeyframeData;
import com.ticxo.modelengine.api.animation.keyframe.data.KeyframeReaderRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.function.Function;

public final class YsmMolangPlugin extends JavaPlugin {
    private EntityVariableStore variables;
    private RateLimitedDiagnostics diagnostics;
    private MolangCompilerFacade compiler;
    private MolangEvaluationContext evaluationContext;
    private MolangKeyframeReader reader;
    private KeyframeReaderRegistry keyframeReaders;
    private YsmMigrationService migrationService;

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
        if (getConfig().getBoolean("migration.enabled", true)) {
            registerMigration();
        }
    }

    @Override
    public void onDisable() {
        if (migrationService != null) migrationService.close();
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

    private void registerMigration() {
        try {
            Path dataRoot = getDataFolder().toPath().toAbsolutePath().normalize();
            Path inputDirectory = resolveDataPath(dataRoot,
                    getConfig().getString("migration.input-directory", "imports"));
            Path outputDirectory = resolveDataPath(dataRoot,
                    getConfig().getString("migration.output-directory", "exports"));
            Path nativeLibrary = resolveDataPath(dataRoot,
                    getConfig().getString("migration.native-library", "native/YSMParserJNI.dll"));

            migrationService = new YsmMigrationService(
                    this, inputDirectory, outputDirectory, nativeLibrary);
            YsmMolangCommand executor = new YsmMolangCommand(this, migrationService);
            PluginCommand command = getCommand("ysmmolang");
            if (command == null) {
                migrationService.close();
                migrationService = null;
                getLogger().warning("Migration command is missing from plugin.yml.");
                return;
            }
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            migrationService.prepareAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    getLogger().warning("Unable to prepare migration directories: " + cause.getMessage());
                }
            });
            getLogger().info("Registered asynchronous YSM migration command /ysmmolang.");
        } catch (RuntimeException exception) {
            if (migrationService != null) migrationService.close();
            migrationService = null;
            getLogger().warning("YSM migration was not registered: " + exception.getMessage());
        }
    }

    private static Path resolveDataPath(Path dataRoot, String configuredPath) {
        Path configured = Path.of(configuredPath == null ? "" : configuredPath.trim());
        return (configured.isAbsolute() ? configured : dataRoot.resolve(configured))
                .toAbsolutePath().normalize();
    }
}
