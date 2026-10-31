#!/usr/bin/env bash
# Runs the demo on Monocle's headless platform with the VNC server attached.
#
# Environment variables:
#   VNC_PORT     port to listen on                      (default 5900)
#   VNC_BIND     address to bind; 0.0.0.0 = all         (default 127.0.0.1)
#                NOTE: there is no authentication, so only expose it on a trusted network
#   VNC_CAPTURE  robot | scene                          (default robot; falls back to scene if blank)
#   VNC_DEBUG    true to trace pointer/key/format/update events on stderr
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
  -Dvnc.bind="${VNC_BIND:-127.0.0.1}" \
  -Dvnc.capture="${VNC_CAPTURE:-robot}" \
  -Dvnc.debug="${VNC_DEBUG:-false}" \
  spike.vnc.Launcher
