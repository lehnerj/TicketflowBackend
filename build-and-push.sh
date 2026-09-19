#!/usr/bin/env bash
# build-and-push.sh
# Usage: ./build-and-push.sh [tag]        (default tag: 1.0.0)
#
# Builds a multi-arch image (amd64 + arm64) via Podman manifest and pushes
# both a versioned tag and :backend-latest to Docker Hub.
#
# Pre-requisites:
#   - podman >= 4.x
#   - podman machine running (on macOS/Windows: podman machine start)
#   - logged in: podman login docker.io

set -euo pipefail

TAG="${1:-1.0.0}"
REPO="lehnerj1207/ticketflow"
FULL_TAG="${REPO}:backend-${TAG}"
LATEST_TAG="${REPO}:backend-latest"
MANIFEST="ticketflow-backend-manifest"

echo "▶ Maven package (skip tests)"
mvn -q -B package -DskipTests

# Remove any leftover local manifest from a previous run
podman manifest rm "${MANIFEST}" 2>/dev/null || true

echo "▶ Building linux/amd64"
podman build \
  --platform linux/amd64 \
  --manifest "${MANIFEST}" \
  .

echo "▶ Building linux/arm64"
podman build \
  --platform linux/arm64 \
  --manifest "${MANIFEST}" \
  .

echo "▶ Pushing manifest as ${FULL_TAG} and ${LATEST_TAG}"
podman manifest push --all "${MANIFEST}" "docker.io/${FULL_TAG}"
podman manifest push --all "${MANIFEST}" "docker.io/${LATEST_TAG}"

# Clean up local manifest
podman manifest rm "${MANIFEST}" 2>/dev/null || true

echo "✅ Pushed ${FULL_TAG} and ${LATEST_TAG}"
