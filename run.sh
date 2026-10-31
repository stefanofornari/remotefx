#!/usr/bin/env bash
# Runs the demo on Monocle's headless platform with the VNC server on 127.0.0.1:5900.
# Add -Dvnc.debug=true below to trace pointer/key events on stderr.
set -euo pipefail
cd "$(dirname "$0")"

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt

exec java -cp "target/classes:$(cat target/cp.txt)" \
  -Dglass.platform=Monocle \
  -Dmonocle.platform=Headless \
  -Dprism.order=sw \
  -Dprism.text=t2k \
  -Dheadless.geometry=1280x800-32 \
  -Dvnc.port="${VNC_PORT:-5900}" \
  spike.vnc.Launcher
