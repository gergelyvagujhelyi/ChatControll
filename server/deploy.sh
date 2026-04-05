#!/bin/bash
# Pull-based deploy: checks for new commits on develop and redeploys if changed.
# Install in cron: */5 * * * * /path/to/ChatControll/server/deploy.sh >> /var/log/chatcontroll-deploy.log 2>&1

cd "$(dirname "$0")"

git fetch origin develop --quiet
LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse origin/develop)

if [ "$LOCAL" != "$REMOTE" ]; then
    echo "$(date): Deploying $LOCAL -> $REMOTE"
    git reset --hard origin/develop
    docker compose build --no-cache server
    docker compose up -d --force-recreate server
    docker image prune -f
    echo "$(date): Deploy complete"
fi
