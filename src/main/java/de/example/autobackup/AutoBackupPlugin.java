package de.example.autobackup;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AutoBackupPlugin extends JavaPlugin {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask timer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        BackupCommand handler = new BackupCommand(this);
        getCommand("backup").setExecutor(handler);
        getCommand("backup").setTabCompleter(handler);
        schedule();
    }

    @Override
    public void onDisable() {
        if (timer != null) timer.cancel();
    }

    /** (Neu) planen anhand der Config. */
    public void schedule() {
        if (timer != null) timer.cancel();
        long minutes = getConfig().getLong("interval-minutes", 60);
        if (minutes <= 0) {
            getLogger().info("Automatische Backups sind deaktiviert (interval-minutes: 0).");
            return;
        }
        long ticks = minutes * 60L * 20L;
        timer = getServer().getScheduler().runTaskTimer(this, () -> startBackup(null), ticks, ticks);
        getLogger().info("Automatisches Backup alle " + minutes + " Minuten.");
    }

    public Path backupDir() {
        return Path.of(getConfig().getString("backup-folder", "backups")).toAbsolutePath().normalize();
    }

    /** Startet ein Backup. Muss im Hauptthread aufgerufen werden. */
    public void startBackup(CommandSender who) {
        if (!running.compareAndSet(false, true)) {
            announce(who, "Es laeuft bereits ein Backup.");
            return;
        }
        announce(who, "Backup gestartet ...");

        // 1) Im Hauptthread: Welten speichern und Autosave kurz pausieren
        List<World> paused = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        try {
            for (World w : getServer().getWorlds()) {
                w.save();
                if (w.isAutoSave()) {
                    w.setAutoSave(false);
                    paused.add(w);
                }
                sources.add(w.getWorldFolder().toPath().toAbsolutePath().normalize());
            }
            getServer().savePlayers();
        } catch (Exception e) {
            paused.forEach(w -> w.setAutoSave(true));
            running.set(false);
            announce(who, "Backup fehlgeschlagen beim Speichern: " + e.getMessage());
            return;
        }
        if (getConfig().getBoolean("include-plugins", false)) {
            sources.add(getDataFolder().getParentFile().toPath().toAbsolutePath().normalize());
        }
        Path dir = backupDir();
        int keep = getConfig().getInt("keep", 10);

        // 2) Asynchron: ZIP schreiben
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String msg = runBackup(sources, dir, keep);
            // 3) Im Hauptthread: aufraeumen
            try {
                getServer().getScheduler().runTask(this, () -> {
                    paused.forEach(w -> w.setAutoSave(true));
                    running.set(false);
                    announce(who, msg);
                });
            } catch (Exception ignored) {
                // Plugin wurde waehrend des Backups deaktiviert
            }
        });
    }

    private String runBackup(List<Path> sources, Path dir, int keep) {
        try {
            Files.createDirectories(dir);
            Path zip = dir.resolve("backup-" + FMT.format(LocalDateTime.now()) + ".zip");
            AtomicInteger skipped = new AtomicInteger();

            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                for (Path src : sources) {
                    addTree(out, src, src.getFileName().toString(), zip, dir, skipped);
                }
            }
            cleanup(dir, keep);
            long mb = Files.size(zip) / (1024 * 1024);
            return "Backup fertig: " + zip.getFileName() + " (" + mb + " MB)"
                    + (skipped.get() > 0 ? ", " + skipped.get() + " Dateien uebersprungen" : "");
        } catch (Exception e) {
            getLogger().severe("Backup-Fehler: " + e);
            return "Backup fehlgeschlagen: " + e.getMessage();
        }
    }

    private void addTree(ZipOutputStream out, Path base, String prefix, Path zipFile, Path backupDir,
                         AtomicInteger skipped) throws IOException {
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Backup-Ordner nie selbst sichern (falls er in einem Quellordner liegt)
                return dir.equals(backupDir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.equals(zipFile) || file.getFileName().toString().equals("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                String name = prefix + "/" + base.relativize(file).toString().replace('\\', '/');
                try {
                    out.putNextEntry(new ZipEntry(name));
                    Files.copy(file, out);
                    out.closeEntry();
                } catch (IOException e) {
                    skipped.incrementAndGet();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                skipped.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void cleanup(Path dir, int keep) throws IOException {
        if (keep <= 0) return;
        List<Path> zips = listBackups(dir);
        for (int i = 0; i < zips.size() - keep; i++) {
            Files.deleteIfExists(zips.get(i));
        }
    }

    /** Alle Backups, aelteste zuerst. */
    public List<Path> listBackups(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("backup-")
                            && p.getFileName().toString().endsWith(".zip"))
                    .sorted()
                    .toList();
        }
    }

    private void announce(CommandSender who, String msg) {
        getLogger().info(msg);
        if (who != null && !(who instanceof ConsoleCommandSender)) {
            who.sendMessage("[AutoBackup] " + msg);
        }
    }
}
