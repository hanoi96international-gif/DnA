#!/usr/bin/env bash
# Übernimmt ein Artefakt aus einem früheren Lauf dieses Repos (z. B. dem PR vor dem Merge) und entpackt
# es nach DEST. Der Name enthält den Git-Tree des Quellstands: gleicher Name heißt gleicher Code, die
# App muss auf main nicht noch einmal eine Stunde gebaut werden. Ohne Treffer Exit 1 (dann wird gebaut).
# Nur Läufe aus diesem Repo zählen, nicht aus Forks (die könnten ein Artefakt mit passendem Namen anlegen).
set -euo pipefail
name="$1"
dest="$2"
repo="$GITHUB_REPOSITORY"
repo_id=$(gh api "repos/$repo" --jq .id)
id=$(gh api "repos/$repo/actions/artifacts?name=$name&per_page=30" --jq \
  "[.artifacts[] | select(.expired == false and .workflow_run.repository_id == $repo_id and .workflow_run.head_repository_id == $repo_id)] | first | .id // empty") || id=""
if [ -z "$id" ]; then
  echo "Kein früherer Build für $name, baue neu"
  exit 1
fi
zip="$RUNNER_TEMP/$name.zip"
gh api "repos/$repo/actions/artifacts/$id/zip" > "$zip"
mkdir -p "$dest"
unzip -q -o "$zip" -d "$dest"
rm -f "$zip"
echo "Build aus früherem Lauf übernommen: $name (Artefakt $id)"
