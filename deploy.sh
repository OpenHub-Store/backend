#!/usr/bin/env bash
set -euo pipefail

# GitHub Store Backend — deploy to production VPS
# Usage: ./deploy.sh <server-ip>

SERVER_IP="${1:?Usage: ./deploy.sh <server-ip>}"
SSH_USER="root"
REMOTE_DIR="/opt/github-store-backend"

echo "==> Deploying to $SERVER_IP..."

# Sync project files to VPS (excludes dev/build artifacts)
rsync -avz --delete \
    --exclude '.git/' \
    --exclude '.gradle/' \
    --exclude 'build/' \
    --exclude '.idea/' \
    --exclude '.env' \
    --exclude 'docker-compose.override.yml' \
    --exclude '.DS_Store' \
    --exclude '.claude/' \
    ./ "$SSH_USER@$SERVER_IP:$REMOTE_DIR/"

echo "==> Building and starting services..."
ssh "$SSH_USER@$SERVER_IP" "cd $REMOTE_DIR && docker compose -f docker-compose.prod.yml up -d --build"

echo "==> Waiting for health check..."
sleep 15
ssh "$SSH_USER@$SERVER_IP" "docker exec github-store-backend-app-1 curl -sf http://localhost:8080/v1/health || echo 'Health check failed!'"

echo "==> Deploy complete!"
