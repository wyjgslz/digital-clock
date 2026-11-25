#!/usr/bin/env bash
# 用法: ./publish.sh <TOKEN> [repo-name] [public|private]
set -euo pipefail
TOKEN="$1"; REPO="${2:-digital-clock}"; VIS="${3:-public}"
API=https://api.github.com
H=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

USER_JSON=$(curl -s "${H[@]}" "$API/user")
OWNER=$(echo "$USER_JSON" | python3 -c 'import sys,json;print(json.load(sys.stdin)["login"])')
echo "账号: $OWNER"

CODE=$(curl -s -o /tmp/repo.json -w '%{http_code}' "${H[@]}" -X POST "$API/user/repos" \
  -d "{\"name\":\"$REPO\",\"private\":$([ "$VIS" = private ] && echo true || echo false),\"description\":\"纯数字全屏时钟：农历 / 天气 / 整点报时 / 可调样式，安卓\",\"has_issues\":true,\"has_wiki\":false}")
echo "建仓 HTTP $CODE"
cat /tmp/repo.json | head -c 300; echo
