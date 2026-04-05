#!/bin/bash
set -eo pipefail
export PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
# Pull-based deploy: checks for new commits on develop and redeploys if changed.
# Install in cron: */5 * * * * /path/to/ChatControll/server/deploy.sh >> /var/log/chatcontroll-deploy.log 2>&1

LOCK="/tmp/chatcontroll-deploy.lock"
exec 200>"$LOCK"
flock -n 200 || { echo "$(date): Deploy already running, skipping"; exit 0; }

cd "$(dirname "$0")"

LAST_DEPLOY_FILE="/tmp/chatcontroll-last-deploy"
git fetch origin develop --quiet
LOCAL=$(cat "$LAST_DEPLOY_FILE" 2>/dev/null || echo "none")
REMOTE=$(git rev-parse origin/develop)

# Always ensure working tree matches origin/develop
git reset --hard origin/develop --quiet

if [ "$LOCAL" = "$REMOTE" ]; then
    exit 0
fi

# Validate LOCAL commit still exists (could be GC'd after force-push)
if [ "$LOCAL" != "none" ] && ! git rev-parse --verify "$LOCAL^{commit}" >/dev/null 2>&1; then
    echo "$(date): Previous commit $LOCAL no longer exists, forcing deploy"
    LOCAL="none"
fi

SERVER_CHANGES=$(git diff --name-only "$LOCAL" "$REMOTE" -- server/ 2>/dev/null || echo "unknown")
if [ "$LOCAL" = "none" ] || [ -n "$SERVER_CHANGES" ]; then
    echo "$(date): Server changed ($LOCAL -> $REMOTE), deploying"
    docker compose build server
    docker compose up -d --force-recreate server
    docker image prune -f
    echo "$(date): Deploy complete"
else
    echo "$(date): New commits ($LOCAL -> $REMOTE) but server/ unchanged, skipping deploy"
fi

echo "$REMOTE" > "$LAST_DEPLOY_FILE"
