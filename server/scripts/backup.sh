#!/bin/sh
# Резервное копирование базы данных вне расписания и удаление копий старше срока хранения.
# Использование: scripts/backup.sh [каталог] [срок хранения, дней]
set -e
DIR="${1:-var/backups}"
KEEP_DAYS="${2:-92}"
python -m app.manage backup --dir "$DIR"
find "$DIR" -name 'finhelper_*.dump' -mtime +"$KEEP_DAYS" -delete
