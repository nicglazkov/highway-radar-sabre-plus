#!/usr/bin/env bash
# Writes a JSON snapshot of per-release APK download counts to stdout.
# Usage: GH_REPO=owner/name bash download-metrics.sh > downloads.json
# One page of 100 releases is plenty for this project.
set -euo pipefail

REPO="${GH_REPO:-nicglazkov/highway-radar-sabre-plus}"
DATE="$(date -u +%F)"

gh api "repos/$REPO/releases?per_page=100" --jq "
  . as \$all
  | {
      date: \"$DATE\",
      repo: \"$REPO\",
      total_downloads: ([ \$all[].assets[].download_count ] | add // 0),
      releases: ([ \$all[] | {
          tag: .tag_name,
          published_at: .published_at,
          downloads: ([ .assets[].download_count ] | add // 0),
          assets: [ .assets[] | { name: .name, download_count: .download_count } ]
        } ] | sort_by(.published_at))
    }"
