#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIKI_SOURCE="${ROOT_DIR}/docs/wiki"
WIKI_REMOTE="${WIKI_REMOTE:-git@github-linjohn8:LinJohn8/minecraft-playerprobe-ai-mod.wiki.git}"
WIKI_BRANCH="${WIKI_BRANCH:-master}"

if [[ ! -d "${WIKI_SOURCE}" ]]; then
  echo "Missing wiki source directory: ${WIKI_SOURCE}" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

git -C "${WORK_DIR}" init
git -C "${WORK_DIR}" config user.name "$(git -C "${ROOT_DIR}" config user.name)"
git -C "${WORK_DIR}" config user.email "$(git -C "${ROOT_DIR}" config user.email)"
cp "${WIKI_SOURCE}"/*.md "${WORK_DIR}/"
git -C "${WORK_DIR}" add .
git -C "${WORK_DIR}" commit -m "docs: sync PlayerProbe wiki"
git -C "${WORK_DIR}" branch -M "${WIKI_BRANCH}"
git -C "${WORK_DIR}" remote add origin "${WIKI_REMOTE}"
git -C "${WORK_DIR}" push -u origin "${WIKI_BRANCH}" --force

echo "Synced wiki pages from ${WIKI_SOURCE}"
