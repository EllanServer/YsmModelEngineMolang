package com.ysm.modelengine.molang;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ysm.converter.BlockbenchToModelEngine;
import com.ysm.converter.YsmToBlockbench;
import com.ysm.converter.YsmToMythicMobs;
import com.ysm.parser.YSMParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Runs the complete YSM migration pipeline away from the Paper/Folia main
 * thread. A single worker is intentional because the bundled JNI parser is not
 * assumed to be reentrant.
 */
final class YsmMigrationService implements AutoCloseable {
    private static final String BUNDLED_NATIVE_RESOURCE = "native/YSMParserJNI.dll";

    private final Logger logger;
    private final Function<String, InputStream> resourceLoader;
    private final Path inputDirectory;
    private final Path outputDirectory;
    private final Path nativeLibrary;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger pendingJobs = new AtomicInteger();
    private final AtomicReference<String> activeFile = new AtomicReference<>();

    YsmMigrationService(YsmMolangPlugin plugin, Path inputDirectory,
                        Path outputDirectory, Path nativeLibrary) {
        this(plugin.getLogger(), plugin::getResource,
                inputDirectory, outputDirectory, nativeLibrary);
    }

    YsmMigrationService(Logger logger, Function<String, InputStream> resourceLoader,
                        Path inputDirectory, Path outputDirectory, Path nativeLibrary) {
        this.logger = logger;
        this.resourceLoader = resourceLoader;
        this.inputDirectory = inputDirectory.toAbsolutePath().normalize();
        this.outputDirectory = outputDirectory.toAbsolutePath().normalize();
        this.nativeLibrary = nativeLibrary.toAbsolutePath().normalize();
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "ysm-model-migration");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, error) ->
                    logger.severe("YSM migration worker failed: " + error.getMessage()));
            return thread;
        });
    }

    CompletableFuture<Void> prepareAsync() {
        return submit(() -> {
            Files.createDirectories(inputDirectory);
            Files.createDirectories(outputDirectory);
            Path parent = nativeLibrary.getParent();
            if (parent != null) Files.createDirectories(parent);
            return null;
        });
    }

    CompletableFuture<MigrationResult> migrate(String requestedFile) {
        return submit(() -> migrateBlocking(requestedFile));
    }

    CompletableFuture<String> workerThreadNameAsync() {
        return submit(() -> Thread.currentThread().getName());
    }

    Path inputDirectory() {
        return inputDirectory;
    }

    Path outputDirectory() {
        return outputDirectory;
    }

    int pendingJobs() {
        return pendingJobs.get();
    }

    String activeFile() {
        return activeFile.get();
    }

    private MigrationResult migrateBlocking(String requestedFile) throws Exception {
        Path input = resolveInput(requestedFile);
        String fileName = input.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".ysm".length());
        activeFile.set(fileName);
        try {
            ensureNativeLibrary();
            YSMParser.ensureLoaded(nativeLibrary);

            Path modelRoot = outputDirectory.resolve(baseName).normalize();
            if (!modelRoot.startsWith(outputDirectory)) {
                throw new IllegalArgumentException("非法模型名称: " + baseName);
            }
            Path parsedDirectory = modelRoot.resolve("parsed");
            Path bbmodelDirectory = modelRoot.resolve("bbmodels");
            Path modelEngineDirectory = modelRoot.resolve("modelengine").resolve(baseName);
            Path mythicMobsDirectory = modelRoot.resolve("mythicmobs");
            Files.createDirectories(parsedDirectory);
            Files.createDirectories(bbmodelDirectory);
            Files.createDirectories(modelEngineDirectory);
            Files.createDirectories(mythicMobsDirectory);

            boolean parsed = YSMParser.parse(input.toString(), parsedDirectory.toString());
            if (!parsed) throw new IllegalStateException("YSMParser 返回失败: " + fileName);

            Path bbmodel = bbmodelDirectory.resolve(baseName + ".bbmodel");
            YsmToBlockbench.convert(parsedDirectory, bbmodel);
            BlockbenchToModelEngine.convert(bbmodel, modelEngineDirectory);
            YsmToMythicMobs.convert(parsedDirectory, mythicMobsDirectory, baseName, bbmodel);

            ExportStats stats = inspect(bbmodel);
            return new MigrationResult(
                    fileName, modelRoot, bbmodel, modelEngineDirectory,
                    mythicMobsDirectory.resolve(baseName + ".yml"), stats
            );
        } finally {
            activeFile.compareAndSet(fileName, null);
        }
    }

    private Path resolveInput(String requestedFile) {
        if (requestedFile == null || requestedFile.isBlank()) {
            throw new IllegalArgumentException("请指定 imports 目录中的 .ysm 文件名");
        }
        Path relative = Path.of(requestedFile.trim());
        if (relative.isAbsolute()) throw new IllegalArgumentException("只允许 imports 目录内的相对路径");
        Path input = inputDirectory.resolve(relative).normalize();
        if (!input.startsWith(inputDirectory)) throw new IllegalArgumentException("不允许访问 imports 目录之外的文件");
        if (!input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm")) {
            throw new IllegalArgumentException("迁移文件必须以 .ysm 结尾");
        }
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("找不到迁移文件: " + input);
        return input;
    }

    private void ensureNativeLibrary() throws IOException {
        if (Files.isRegularFile(nativeLibrary)) return;
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win")) {
            throw new IllegalStateException(
                    "内置 JNI 仅提供 Windows 版本；请在配置路径放置当前系统的 YSMParser JNI 库: " + nativeLibrary);
        }
        Path parent = nativeLibrary.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (InputStream input = resourceLoader.apply(BUNDLED_NATIVE_RESOURCE)) {
            if (input == null) throw new IOException("插件 JAR 中缺少 " + BUNDLED_NATIVE_RESOURCE);
            Files.copy(input, nativeLibrary, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ExportStats inspect(Path bbmodelPath) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(bbmodelPath, StandardCharsets.UTF_8)).getAsJsonObject();
        int elements = root.has("elements") ? root.getAsJsonArray("elements").size() : 0;
        int textures = root.has("textures") ? root.getAsJsonArray("textures").size() : 0;
        int animations = root.has("animations") ? root.getAsJsonArray("animations").size() : 0;
        int groups = root.has("outliner") ? countGroups(root.getAsJsonArray("outliner")) : 0;

        Counter counter = new Counter();
        Set<String> uniqueMolang = new HashSet<>();
        inspectElement(root, counter, uniqueMolang);
        return new ExportStats(
                elements, groups, textures, animations,
                counter.keyframes, counter.prePostKeyframes,
                counter.molangValues, uniqueMolang.size()
        );
    }

    private static int countGroups(JsonArray outliner) {
        int count = 0;
        for (JsonElement element : outliner) {
            if (!element.isJsonObject()) continue;
            count++;
            JsonObject group = element.getAsJsonObject();
            if (group.has("children") && group.get("children").isJsonArray()) {
                count += countGroups(group.getAsJsonArray("children"));
            }
        }
        return count;
    }

    private static void inspectElement(JsonElement element, Counter counter, Set<String> uniqueMolang) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString();
                if (value.startsWith("molang:")) {
                    counter.molangValues++;
                    uniqueMolang.add(value);
                }
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) inspectElement(child, counter, uniqueMolang);
            return;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("channel") && object.has("data_points")) {
            counter.keyframes++;
            JsonElement points = object.get("data_points");
            if (points.isJsonArray() && points.getAsJsonArray().size() == 2) counter.prePostKeyframes++;
        }
        for (var entry : object.entrySet()) inspectElement(entry.getValue(), counter, uniqueMolang);
    }

    private <T> CompletableFuture<T> submit(ThrowingSupplier<T> task) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("迁移服务已关闭"));
        pendingJobs.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } catch (Exception exception) {
                    throw new MigrationException(exception);
                } finally {
                    pendingJobs.decrementAndGet();
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            pendingJobs.decrementAndGet();
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }

    record MigrationResult(
            String inputFile,
            Path outputRoot,
            Path bbmodel,
            Path modelEngineDirectory,
            Path mythicMobsFile,
            ExportStats stats
    ) {
    }

    record ExportStats(
            int elements,
            int groups,
            int textures,
            int animations,
            int keyframes,
            int prePostKeyframes,
            int molangValues,
            int uniqueMolangValues
    ) {
    }

    private static final class Counter {
        private int keyframes;
        private int prePostKeyframes;
        private int molangValues;
    }

    private static final class MigrationException extends RuntimeException {
        private MigrationException(Throwable cause) {
            super(cause);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
