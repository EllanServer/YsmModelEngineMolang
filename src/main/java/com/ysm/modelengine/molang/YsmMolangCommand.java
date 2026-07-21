package com.ysm.modelengine.molang;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;

final class YsmMolangCommand implements CommandExecutor, TabCompleter {
    private final YsmMolangPlugin plugin;
    private final YsmMigrationService migrations;

    YsmMolangCommand(YsmMolangPlugin plugin, YsmMigrationService migrations) {
        this.plugin = plugin;
        this.migrations = migrations;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "migrate" -> queueMigration(sender, label, args);
            case "status" -> sendStatus(sender);
            case "paths" -> sendPaths(sender);
            default -> sendUsage(sender, label);
        }
        return true;
    }

    private void queueMigration(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("用法: /" + label + " migrate <imports目录内的文件.ysm>");
            return;
        }
        String requestedFile = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        int queuedBefore = migrations.pendingJobs();
        sender.sendMessage("YSM 迁移已加入异步队列，不会占用服务器主线程。队列前方任务: " + queuedBefore);

        migrations.migrate(requestedFile).whenComplete((result, error) -> notifyOnGlobalThread(sender, () -> {
            if (error != null) {
                Throwable cause = rootCause(error);
                sender.sendMessage("YSM 迁移失败: " + safeMessage(cause));
                plugin.getLogger().warning("YSM migration failed for " + requestedFile + ": " + safeMessage(cause));
                return;
            }

            YsmMigrationService.ExportStats stats = result.stats();
            sender.sendMessage("YSM 迁移完成: " + result.inputFile());
            sender.sendMessage("输出目录: " + result.outputRoot());
            sender.sendMessage("几何: " + stats.elements() + " cubes / " + stats.groups()
                    + " groups / " + stats.textures() + " textures");
            sender.sendMessage("动画: " + stats.animations() + " animations / " + stats.keyframes()
                    + " keyframes / " + stats.molangValues() + " Molang values ("
                    + stats.uniqueMolangValues() + " unique)");
        }));
    }

    private void sendStatus(CommandSender sender) {
        String active = migrations.activeFile();
        sender.sendMessage("异步迁移队列: " + migrations.pendingJobs() + " 个任务");
        sender.sendMessage(active == null ? "当前没有正在处理的模型" : "正在迁移: " + active);
    }

    private void sendPaths(CommandSender sender) {
        sender.sendMessage("导入目录: " + migrations.inputDirectory());
        sender.sendMessage("导出目录: " + migrations.outputDirectory());
    }

    private static void sendUsage(CommandSender sender, String label) {
        sender.sendMessage("/" + label + " migrate <file.ysm> - 异步执行完整迁移");
        sender.sendMessage("/" + label + " status - 查看异步队列");
        sender.sendMessage("/" + label + " paths - 查看导入/导出目录");
    }

    private void notifyOnGlobalThread(CommandSender sender, Runnable notification) {
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, notification);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Unable to deliver migration result to " + sender.getName()
                    + ": " + exception.getMessage());
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof RuntimeException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("migrate", "status", "paths").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }
}
