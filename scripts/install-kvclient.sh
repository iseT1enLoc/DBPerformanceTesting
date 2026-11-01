#!/usr/bin/env bash
# Maven Central only has kvclient 18.3, so copy the client jar that matches the
# server out of the KVLite image and install it into the local Maven repository.
set -euo pipefail

IMAGE="${IMAGE:-ghcr.io/oracle/nosql:latest-ce}"
VERSION="${KV_VERSION:-25.3.21}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; docker rm -f kvclient-extract >/dev/null 2>&1 || true' EXIT

docker create --name kvclient-extract "$IMAGE" >/dev/null
docker cp "kvclient-extract:/app/kv-$VERSION/lib/kvclient-$VERSION.jar" "$TMP/kvclient.jar"

mvn -q install:install-file \
  -Dfile="$TMP/kvclient.jar" \
  -DgroupId=com.oracle.kv -DartifactId=kvclient -Dversion="$VERSION" -Dpackaging=jar

echo "Installed com.oracle.kv:kvclient:$VERSION"
