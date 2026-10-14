# AutoBackup (Paper 1.21.11)

Erstellt automatisch ZIP-Backups aller Welten.

## Befehle (Permission `autobackup.admin`, Standard: OP)
- `/backup now` - sofort ein Backup erstellen
- `/backup list` - vorhandene Backups anzeigen
- `/backup reload` - config.yml neu laden

## Config (`plugins/AutoBackup/config.yml`)
- `interval-minutes` - Abstand in Minuten (0 = aus)
- `keep` - Anzahl behaltener Backups
- `backup-folder` - Zielordner (Standard `backups`)
- `include-plugins` - plugins-Ordner mitsichern

## Bauen
Auf GitHub pushen -> Actions baut die JAR (Artifacts). Oder lokal: `gradle build`.
