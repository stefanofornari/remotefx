# remotefx

Run a JavaFX application on a machine with no screen and use it from another machine with a
standard VNC viewer (macOS Screen Sharing, Remmina, TigerVNC, ...).

remotefx is a small RFB (VNC) server that lives **inside the JavaFX application's JVM**. The app
renders on [Monocle](https://github.com/TestFX/Monocle)'s in-memory headless platform, so no X
server or display is needed. The server reads the rendered screen, sends the changed parts to the
viewer, and turns the viewer's mouse and keyboard events into JavaFX input.

```
 computer1 (viewer)                          computer2 (headless, runs the app)
 ┌──────────────────┐   VNC (RFB, TCP)   ┌──────────────────────────────────────┐
 │ Screen Sharing / │ ◄────────────────► │ JVM: your JavaFX app on Monocle      │
 │ Remmina / ...    │   (use ssh -L or   │      + remotefx VncServer (port 5900)│
 └──────────────────┘    a trusted LAN)  └──────────────────────────────────────┘
```

## Status

A working spike. What has been seen working: the demo app (`run.sh`) on a Linux machine, viewed
from macOS Screen Sharing and from Remmina, with password authentication.

Not yet verified: the Java agent (`run-agent.sh`) on a real application, and running an
application that uses the **module path** (see [Module-path applications](#module-path-applications)).
Those sections say so where it matters.

## Requirements

- JDK 21 or later and Maven (to build).
- On the machine that runs the app: nothing graphical. No X server, no GTK needed on Monocle.

## Quick start: the demo

```bash
git clone https://github.com/stefanofornari/remotefx
cd remotefx
VNC_PASSWORD=secret12 VNC_BIND=0.0.0.0 ./run.sh
```

Then connect a viewer to `<host>:5900`. On macOS: Finder, Go, Connect to Server,
`vnc://<host>:5900`.

**A password is required for macOS Screen Sharing.** It refuses servers that offer no
authentication, so with no `VNC_PASSWORD` it asks for a password that nothing can satisfy.
VNC authentication only uses the first 8 characters of the password.

## Remoting your own application (Java agent)

The agent attaches the server to an existing JavaFX app without any change to its code.

```bash
mvn clean package -DskipTests        # builds target/remotefx.jar (it is also the agent)
```

Add this JVM flag to however you start your application:

```
-javaagent:/path/to/remotefx/target/remotefx.jar=port=5900,bind=0.0.0.0
```

and set `VNC_PASSWORD` in the environment of that JVM. When the application has shown its first
window, the agent starts the server and logs `[vnc] listening on ...` on stderr.

### Running without a display (Monocle)

With no `DISPLAY` (for example in an ssh session) JavaFX fails with
`UnsupportedOperationException: Unable to open DISPLAY`. Select the Monocle headless platform
with these JVM flags:

```
-Dglass.platform=Monocle
-Dmonocle.platform=Headless
-Dprism.order=sw
-Dprism.text=t2k
-Dheadless.geometry=1280x800-32
```

Monocle itself needs no extra jar on your side: `target/remotefx.jar` is a fat jar that already
contains it (Monocle is pure Java, with no native code of its own; the software rendering comes
from JavaFX). Classes in an agent jar are visible to the whole application, so no `-cp` entry is
needed. JavaFX is deliberately not bundled, since it carries per-OS natives and your application
already provides it.

`headless.geometry` is the size and colour depth of the virtual screen, which is what the viewer
shows. Make it large enough for all the windows of
your application to fit side by side (for example `2560x1440-32`): the viewer only shows this
screen, and a window placed outside it cannot be reached. A bigger screen costs more CPU per
captured frame. In the viewer, use its scale-to-fit option if the screen is larger than your
display.

Classpath application, complete example:

```bash
export VNC_PASSWORD=secret12
java -javaagent:/path/to/remotefx/target/remotefx.jar=port=5900,bind=0.0.0.0 \
     -Dglass.platform=Monocle -Dmonocle.platform=Headless \
     -Dprism.order=sw -Dprism.text=t2k -Dheadless.geometry=1280x800-32 \
     -cp yourapp.jar \
     your.Main
```

### Module-path applications

If your application starts with `--module-path ... --add-modules javafx...`, the Monocle classes
(which come with the agent jar) live in the unnamed module and need access to JavaFX internals
that the `javafx.*` modules do not export. Without that, startup fails with:

```
IllegalAccessError: ... class com.sun.glass.ui.monocle.MonoclePlatformFactory ...
cannot access class com.sun.glass.ui.PlatformFactory ... module javafx.graphics does not export
com.sun.glass.ui to unnamed module
```

The agent fixes this itself: at start-up it exports and opens every package of the `javafx.*`
modules to the unnamed module, so no `--add-exports` flags are needed. (With
`VNC_DEBUG=true` it logs `[vnc] opened N packages of javafx.graphics` and so on.) JavaFX on the
plain classpath needs nothing of this.

**Status:** the module-path case is untested beyond the error above. If you would rather not use
this behaviour, add the exports by hand as `--add-exports javafx.graphics/<package>=ALL-UNNAMED`
for each package the error names, and report it so the agent can be fixed.

**Version match.** The published Monocle jars stop at 21.0.2. If your JavaFX is newer, Monocle may
fail on changed internal APIs. Then the alternative below is the fallback.

### Alternative: a virtual X display (Xvfb)

If Monocle does not work with your JavaFX version, give the application a virtual X display.
Nothing else in the application changes, and JavaFX uses its normal GTK backend:

```bash
VNC_PASSWORD=secret12 xvfb-run -a -s "-screen 0 1280x800x24" \
    java -javaagent:/path/to/remotefx/target/remotefx.jar=bind=0.0.0.0 -jar yourapp.jar
```

This needs Xvfb and the GTK libraries on the machine. There is no window manager, so windows have
no title bar and all open at the top-left; install `openbox` or `fluxbox` and start it on the
same `DISPLAY` if you need window dragging and focus handling.

### On a real desktop

You can also use the agent in a normal desktop session, but then the viewer sees the **whole
real screen** (not only your app), and the viewer's mouse moves the physical pointer. That is
useful only for a quick test.

## Options

Agent options are `key=value` pairs separated by commas in the `-javaagent` argument. The same
settings work as system properties (`-Dvnc.port=5900`) and, in `run.sh`, as the environment
variables in the last column.

| Option    | Default     | Meaning                                                              | `run.sh` variable |
|-----------|-------------|----------------------------------------------------------------------|-------------------|
| `port`    | `5900`      | TCP port to listen on                                                | `VNC_PORT`        |
| `bind`    | `127.0.0.1` | Address to bind. `0.0.0.0` listens on all interfaces                 | `VNC_BIND`        |
| `capture` | `robot`     | `robot` grabs the screen; `scene` snapshots each window instead      | `VNC_CAPTURE`     |
| `debug`   | `false`     | Trace pointer, key, pixel-format and update events on stderr         | `VNC_DEBUG`       |

The password is **only** read from the `VNC_PASSWORD` environment variable (so it does not show
up in the process list). There is a `-Dvnc.password` fallback, but it is visible in `ps`.

`capture=robot` falls back to `scene` by itself if the robot returns black frames or fails.

## Security

- With no password, there is no authentication at all. Anyone who can reach the port controls
  the application.
- Even with a password, the connection is **not encrypted**, and VNC authentication is weak (DES
  challenge/response, 8 characters). Do not expose the port to the internet.
- Best practice: leave `bind` at `127.0.0.1` and tunnel it: `ssh -L 5901:localhost:5900 user@host`,
  then connect the viewer to `localhost:5901`. Use `bind=0.0.0.0` only on a trusted network.

## Troubleshooting

| Symptom | Cause and fix |
|---------|---------------|
| macOS asks for a password and rejects whatever you type | The server runs without a password. Set `VNC_PASSWORD` and check that the log says `authentication: password`. |
| `Unable to open DISPLAY` | No display. Use the Monocle flags above, or Xvfb. |
| `JavaFX runtime components are missing` | Running a class that extends `Application` from the classpath. Start it through a plain `main` class that calls `Application.launch`, as `spike.vnc.Launcher` does. |
| Connects but the screen is black | Run with `VNC_DEBUG=true` and look at the `[vnc]` lines: capture mode, window count, `first frame captured`. Try `capture=scene`. Make sure the agent saw a showing window. |
| A window is cut off or unreachable | The virtual screen is too small for the application's windows. Raise `-Dheadless.geometry=WIDTHxHEIGHT-32`. |
| Mouse works but typing does nothing | Keys go to the window you last clicked. Click inside the window (on the text field) first. Run with `VNC_DEBUG=true` and check the `key target:` lines: they show which window and node receive the keys. |
| Cannot connect from another machine | Server bound to `127.0.0.1` (the default). Set `bind=0.0.0.0` and check the firewall. |
| `IllegalAccessError` mentioning a `com.sun...` package | Module-path app: add the matching `--add-exports`, see above. |
| Keys type the wrong character | Keys are sent as synthetic `KeyEvent`s using the keysym the viewer sends. Report the layout and the characters. |

## Limitations

- Raw encoding only, so bandwidth is high on slow links. The server compares 64x64 tiles and sends
  only the changed ones, but it grabs the screen about 25 times per second while a viewer is
  connected, even when nothing changes.
- All viewers share the one screen.
- **No window decorations.** Monocle has no window manager, so there are no title bars and you cannot
  move, resize or close windows with the mouse. Windows appear where the application puts them.
  Running under Xvfb with a window manager (see above) gives real decorations.
- No clipboard transfer, no cursor shape, no encryption.
- `Alt` is ignored for text input so that AltGr characters (`@`, `#`, ...) can be typed, which
  means `Alt+letter` mnemonics do not work.
- Scaled (HiDPI) screens are not handled: the capture size must equal the screen size.

## How it works

- `VncServer`: the RFB 3.8 server (handshake, password challenge, framebuffer updates, input).
- `VncAgent`: the `premain` entry point that waits for the toolkit and a first window.
- `Demo` and `Launcher`: a small UI with a few controls, to try things out.

## Licence note

`target/remotefx.jar` redistributes `org.testfx:openjfx-monocle`, which is derived from OpenJFX
(GPL v2 with the Classpath Exception). Check its licence before you redistribute the fat jar.
