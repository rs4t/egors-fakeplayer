#!/usr/bin/env bash
# Builds fakeplayer.jar against delight's own server jars.
# Uses server internals (NMS), so rebuild after every Purpur update.
set -euo pipefail
cd "$(dirname "$0")"
SRV=/mnt/data/pelican/volumes/46ea00ed-a0cb-4690-acbf-45033ecdf606
VER=$(sudo ls "$SRV/versions" | sort -V | tail -1)
rm -rf lib out && mkdir -p lib out
sudo sh -c "cp $SRV/versions/$VER/*.jar lib/"
sudo unzip -p "$SRV/server.jar" META-INF/libraries.list | awk '{print $3}' | while read -r p; do sudo cp "$SRV/libraries/$p" lib/; done
sudo chown -R "$(id -u):$(id -g)" lib
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/w -w /w ghcr.io/pelican-eggs/yolks:java_25 sh -c \
  'javac -Xlint:-options --release 25 -cp "lib/*" -d out $(find src -name "*.java") && cp resources/* out/ && jar --create --file fakeplayer.jar -C out .'
rm -rf lib out
echo "built $(pwd)/fakeplayer.jar for $VER"
