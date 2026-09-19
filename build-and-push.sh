#!/usr/bin/env bash
# build-and-push.sh
# Usage: ./build-and-push.sh [tag] [--deploy]
#
#   tag        Image version tag (default: 1.0.0)
#   --deploy   After pushing, trigger a rolling restart of the Deployment so
#              the cluster always pulls the freshly pushed image — even when
#              the tag has not changed.  Requires kubectl to be configured.
#
# Builds a multi-arch image (amd64 + arm64) via Podman manifest and pushes
# both a versioned tag and :backend-latest to Docker Hub.
#
# Pre-requisites:
#   - podman >= 4.x
#   - podman machine running (on macOS/Windows: podman machine start)
#   - logged in: podman login docker.io

set -euo pipefail

TAG="1.0.0"
DEPLOY=false

for arg in "$@"; do
  case "$arg" in
    --deploy) DEPLOY=true ;;
    *)        TAG="$arg"  ;;
  esac
done

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

# ── Optional rolling restart ───────────────────────────────────────────────────
# imagePullPolicy: Always in the Deployment ensures the new image is pulled,
# but only when a Pod is actually (re)started.  kubectl apply on an unchanged
# manifest does nothing, so we trigger an explicit rollout restart here.
if [ "${DEPLOY}" = true ]; then
  echo "▶ Triggering rolling restart of ticketflow-backend …"
  kubectl rollout restart deployment/ticketflow-backend -n ticketflow
  kubectl rollout status  deployment/ticketflow-backend -n ticketflow
  echo "✅ Rollout complete"
fi
