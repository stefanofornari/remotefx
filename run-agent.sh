#!/usr/bin/env bash
# Same demo as run.sh, but the app does NOT start the VNC server itself: the Java agent attaches it.
# This is how you remote an existing JavaFX app without changing its code:
#   java -javaagent:target/remotefx.jar=port=5900,bind=0.0.0.0 <your usual JVM flags and main class>
# Agent options: port, bind, capture, debug. The password is read from VNC_PASSWORD.
# Monocle flags (-Dglass.platform=Monocle ...) are only needed to run without a display.
set -euo pipefail
cd "$(dirname "$0")"

mvn -q package -DskipTests dependency:build-classpath -Dmdep.outputFile=target/cp.txt

exec java -cp "target/classes:$(cat target/cp.txt)" \
  -javaagent:target/remotefx.jar="port=${VNC_PORT:-5900},bind=${VNC_BIND:-127.0.0.1},capture=${VNC_CAPTURE:-robot},debug=${VNC_DEBUG:-false}" \
  -Dglass.platform=Monocle \
  -Dmonocle.platform=Headless \
  -Dprism.order=sw \
  -Dprism.text=t2k \
  -Dheadless.geometry=1280x800-32 \
  -Ddemo.noserver=true \
  spike.vnc.Launcher
