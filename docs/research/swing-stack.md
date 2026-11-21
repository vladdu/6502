# Swing stack for the Front panel

Research for [#36](https://github.com/vladdu/6502/issues/36), part of map [#30](https://github.com/vladdu/6502/issues/30). Checked 2026-11-21 against JDK 25 (`openjdk 25 2025-09-16 LTS`, the version on this machine and in CI).

"Verified locally" means a throwaway probe (FlatLaf 3.8 + JDK 25, run with `java Probe.java`) showed the behaviour on this WSL2 machine under `xvfb-run`. The probe is not committed.

## 1. FlatLaf: version, coordinates, setup

Facts:

- Latest release: **3.8**, published to Maven Central 2026-11-04 ([maven-metadata.xml](https://repo1.maven.org/maven2/com/formdev/flatlaf/maven-metadata.xml), [releases](https://github.com/JFormDesigner/FlatLaf/releases)).
- Coordinates: `com.formdev:flatlaf:3.8` ([FlatLaf home](https://www.formdev.com/flatlaf/)). Licence Apache-2.0 (POM). The POM declares **no dependencies**. Jar is 1,051,716 bytes ([3.8 jar](https://repo1.maven.org/maven2/com/formdev/flatlaf/3.8/)); it includes small native libraries for Windows, Linux and macOS (about 30 to 110 KB each) used for window decorations and similar.
- Runs on Java 8 or newer; ships a JPMS module named `com.formdev.flatlaf` ([GitHub README](https://github.com/JFormDesigner/FlatLaf), [FlatLaf home](https://www.formdev.com/flatlaf/)).
- Setup: call once in `main` before creating any Swing component ([FlatLaf home](https://www.formdev.com/flatlaf/)):
  ```java
  FlatLightLaf.setup();   // or FlatDarkLaf / FlatIntelliJLaf / FlatDarculaLaf
  ```
  Core theme classes ship in the main jar: `com.formdev.flatlaf.FlatLightLaf`, `FlatDarkLaf`, `FlatIntelliJLaf`, `FlatDarculaLaf`, `com.formdev.flatlaf.themes.FlatMacLightLaf`, `FlatMacDarkLaf` ([themes](https://www.formdev.com/flatlaf/themes/)).
- Fat jar: the README says to keep `Multi-Release: true` in the manifest, keep `META-INF/versions/` and `META-INF/services/`, and merge same-named service files ([README](https://github.com/JFormDesigner/FlatLaf)). The 3.8 jar does have `META-INF/versions/9/...` classes.
- 3.7.2 notes a Linux issue only with JetBrains JBR 25 plus `-Dawt.toolkit.name=WLToolkit` (Wayland toolkit): FlatLaf window decorations disabled there ([releases](https://github.com/JFormDesigner/FlatLaf/releases)). It does not affect stock OpenJDK 25 on X11/XWayland.
- Verified locally: `FlatLightLaf.setup()` under JDK 25 with and without `--enable-native-access=ALL-UNNAMED` gave no warnings on Linux.

**Recommendation:** add `com.formdev:flatlaf:3.8`, call `FlatLightLaf.setup()` (or Dark) at the top of the `run --gui` path. Our `pom.xml` shades a fat jar: add `<Multi-Release>true</Multi-Release>` to the existing `ManifestResourceTransformer` `manifestEntries` (`ServicesResourceTransformer` and `Enable-Native-Access` are already there). No transitive dependencies.

## 2. Pane layout: nested JSplitPane vs a docking library

Facts:

- `JSplitPane` is in `java.desktop`; nesting split panes gives a fixed, resizable tiling with no extra dependency ([JDK 25 JSplitPane](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/swing/JSplitPane.html)). Verified locally: a vertical split holding a horizontal split renders under FlatLaf.
- ModernDocking ([repo](https://github.com/andrewauclair/ModernDocking)): MIT licence, Java 11+, README says zero dependencies; optional `modern-docking-ui` module adds FlatLaf integration. Artifacts under `io.github.andrewauclair`: `modern-docking-api` plus `modern-docking-single-app` (or `-multi-app`), optionally `modern-docking-ui`.
  - Latest: **1.4.9**, 2026-06-10 ([Maven Central listing](https://repo1.maven.org/maven2/io/github/andrewauclair/modern-docking-api/)). Size: `modern-docking-api-1.4.9.jar` 226,792 bytes + `modern-docking-single-app-1.4.9.jar` 15,089 bytes, about 240 KB.
  - Maintenance: single maintainer, 152 stars, 17 open issues; last commit 2026-06-10, before that a burst in March 2026 ([commits](https://github.com/andrewauclair/ModernDocking/commits/main)). Active but slowing, bus factor 1.
  - The README describes the older Swing docking frameworks as outdated and no longer maintained; I did not check them further.

**Recommendation:** nested `JSplitPane`. It needs no dependency, and the map already defers layout persistence. ModernDocking is the fallback if users later want to drag, float or rearrange panes. Adopting it **adds a dependency beyond FlatLaf** (about 240 KB, MIT, one maintainer). A future screen pane fits a split-pane tiling as one more child.

## 3. Console pane taking raw keystrokes; focus switch to the command line

Facts:

- Focus traversal keys are consumed by AWT before any listener sees them: "all KeyEvents related to the focus traversal key, including the associated KEY_TYPED event, will be consumed" ([JDK 25 Focus Spec](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/doc-files/FocusSpec.html)). Default forward traversal is TAB and CTRL-TAB (text areas: CTRL-TAB only).
- `Component.setFocusTraversalKeysEnabled(false)`: "the Component receives all KeyEvents for those keys" ([Focus Spec](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/doc-files/FocusSpec.html)).
- Verified locally: a non-editable `JTextArea` with `setFocusTraversalKeysEnabled(false)` and a `KeyListener.keyTyped` gets these `getKeyChar()` values: `a`=97, Ctrl+C=3, Tab=9, Backspace=8, Enter=10, Esc=27. So control characters arrive already mapped to ASCII control codes in `keyTyped`; no `keyPressed` translation is needed for Ctrl+letter. Enter gives 10 (LF), not 13: decide what the Console Device should receive.
- Verified locally: after `cmd.requestFocusInWindow()` on a `JTextField`, typed keys go to the field, not the Console pane.

**Recommendation:** Console pane = non-editable `JTextArea` (monospaced) in a `JScrollPane`. Call `setFocusTraversalKeysEnabled(false)`, and add a `KeyListener` whose `keyTyped` forwards `getKeyChar()` (as a byte) to the Console Device input and calls `consume()`. Output: append text; handle `\r`, `\b` and bell (`Toolkit.beep()`) in a small tested model below Swing. Command line = a separate `JTextField`. Since Tab is captured by the Console pane, switch focus with a binding that is not a Console byte: a mouse click, or a `JComponent.WHEN_IN_FOCUSED_WINDOW` key binding on a function key (for example F6) that calls `requestFocusInWindow()` on the other component. The exact key is a spec decision. No dependency.

## 4. Refreshing panes with javax.swing.Timer from snapshots

Facts ([JDK 25 javax.swing.Timer](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/swing/Timer.html)):

- "the action event handlers for Timers execute on ... the event-dispatching thread", so they can touch Swing components but "must execute quickly".
- "Timers coalesce events by default": a busy EDT gets one pending tick, not a backlog.
- Verified locally: a Machine-like thread doing `AtomicReference.set(snapshot)` 1000 times, and a 50 ms `Timer` doing `label.setText(ref.get())`, ended showing the final value. The producer never blocked on Swing.

**Recommendation:** the Machine thread publishes an immutable snapshot record into a `volatile` field or `AtomicReference` (latest wins, never waits). A single `javax.swing.Timer` (about 50 to 100 ms, coalescing on by default) reads it on the EDT and updates panes only when the snapshot changed. Do not use `SwingUtilities.invokeLater` per Step. Console output bytes are the exception: they must not be dropped, so drain them from a queue on each tick instead of keeping only the latest value. No dependency.

## 5. Headless smoke test

Facts:

- `Frame()` throws `HeadlessException` "when GraphicsEnvironment.isHeadless() returns true" ([JDK 25 Frame](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/Frame.html)); `JFrame` extends `Frame`.
- Verified locally: with `-Djava.awt.headless=true`, `new JFrame()` throws `HeadlessException`. With `DISPLAY` and `WAYLAND_DISPLAY` unset, JDK 25 on Linux reports headless by itself and throws the same way.
- Verified locally: under `xvfb-run -a java ...` the full probe ran: FlatLaf, window, `java.awt.Robot` key injection, focus switch and Timer. `xvfb-run` is in the `xvfb` package, installed on this machine.
- GitHub Actions `ubuntu-latest` (Ubuntu 24.04, image 20261116) lists `xvfb` among installed apt packages ([runner image readme](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)), so CI needs no install step.
- This machine is WSL2 with WSLg: `DISPLAY=:0`, `WAYLAND_DISPLAY=wayland-0`. A plain `mvn test` here would therefore build real windows on the desktop.

**Recommendation:** the smoke test builds the real Front panel window and pumps one Timer tick. Guard it with `Assumptions.assumeFalse(GraphicsEnvironment.isHeadless())` so it skips cleanly wherever there is no display. In CI, run the build as `xvfb-run -a mvn -B verify -Psinglestep` (one-line change to `.github/workflows/ci.yml`, no new action). Locally it runs against WSLg, or under `xvfb-run` to keep windows off the desktop. No dependency.

## Dependency summary

| Option | Adds | Recommended |
|---|---|---|
| FlatLaf 3.8 | 1 jar, ~1.05 MB, Apache-2.0, no transitive deps | yes (already agreed) |
| Nested `JSplitPane` | nothing (JDK) | yes |
| ModernDocking 1.4.9 | 2 jars, ~240 KB, MIT, single maintainer | no, fallback only |
| Xvfb in CI | nothing (preinstalled on runner) | yes |

## Open points

- Not checked: running the probe directly on WSLg (it would have opened a window on the desktop). WSLg serves X11 on `:0`, so it is expected to work, but that is not verified.
- Which key moves focus between the Console pane and the command line is a spec decision.
- Enter arrives as LF (10). Whether the Console Device should get CR is a spec decision.
