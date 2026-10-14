package de.example.autobackup;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class BackupCommand implements CommandExecutor, TabCompleter {

    private final AutoBackupPlugin plugin;

    public BackupCommand(AutoBackupPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;

        switch (args[0].toLowerCase()) {
            case "now" -> plugin.startBackup(sender);
            case "reload" -> {
                plugin.reloadConfig();
                plugin.schedule();
                sender.sendMessage("[AutoBackup] Config neu geladen.");
            }
            case "list" -> {
                try {
                    List<Path> list = plugin.listBackups(plugin.backupDir());
                    if (list.isEmpty()) {
                        sender.sendMessage("[AutoBackup] Keine Backups vorhanden.");
                        return true;
                    }
                    sender.sendMessage("[AutoBackup] " + list.size() + " Backup(s):");
                    for (Path p : list) {
                        long mb = Files.size(p) / (1024 * 1024);
                        sender.sendMessage(" - " + p.getFileName() + " (" + mb + " MB)");
                    }
                } catch (IOException e) {
                    sender.sendMessage("[AutoBackup] Fehler: " + e.getMessage());
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("now", "list", "reload") : List.of();
    }
}
