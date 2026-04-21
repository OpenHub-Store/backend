#!/usr/bin/env bash
# Nightly Postgres backup for the GitHub Store backend.
#
# Runs inside the VPS (not the container). Uses `docker exec` to pg_dump
# from the running postgres container. Output is a compressed custom-format
# archive (pg_dump -Fc) so it restores with pg_restore. Retention: 14
# daily dumps rotated by file mtime.
#
# Install: deploy.sh rsyncs this file to /opt/github-store-backend/ops/. Link it
# to the path the existing crontab calls:
#   ssh root@<vps> 'ln -sf /opt/github-store-backend/ops/backup_postgres.sh /usr/local/bin/backup_postgres.sh'
# Existing cron: 30 3 * * * /usr/local/bin/backup_postgres.sh >> /var/log/postgres_backup.log 2>&1
#
# Restore:
#   docker exec -i github-store-backend-postgres-1 pg_restore -U githubstore -d githubstore --clean --if-exists < /var/backups/postgres/backup-YYYY-MM-DD.dump
#
# Off-site copy: this script only writes locally. Rsync the dump to a second
# host (or cloud storage) in a separate cron or via a rsync-over-SSH pull
# from a backup machine. Local-only backups protect against container/db
# corruption but not hardware failure.

set -euo pipefail

BACKUP_DIR="/var/backups/postgres"
CONTAINER="github-store-backend-postgres-1"
DB_USER="githubstore"
DB_NAME="githubstore"
RETENTION_DAYS=14
DATE=$(date -u +%Y-%m-%d)
OUT="$BACKUP_DIR/backup-$DATE.dump"

mkdir -p "$BACKUP_DIR"

echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] Starting backup to $OUT"

# pg_dump inside the container, pipe to host file. -Fc = custom format,
# compressed, restorable with pg_restore. Exit non-zero on any error so
# cron records the failure.
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc > "$OUT.tmp"
mv "$OUT.tmp" "$OUT"

SIZE=$(du -h "$OUT" | cut -f1)
echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] Backup complete: $OUT ($SIZE)"

# Rotate: delete dumps older than RETENTION_DAYS.
find "$BACKUP_DIR" -name "backup-*.dump" -type f -mtime +"$RETENTION_DAYS" -print -delete

echo "[$(date -u +%Y-%m-%dT%H:%M:%SZ)] Done. Dumps in $BACKUP_DIR:"
ls -lh "$BACKUP_DIR"/backup-*.dump 2>/dev/null | tail -20
