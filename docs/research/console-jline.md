# Console host terminal handling with JLine (Java 25, Linux/WSL2)

Research for issue #3. Question: can JLine give the Console what it needs on Java 25, and what is the minimal API?

Answer: yes. Use **JLine 4.7.0** (`jline-terminal`, `jline-terminal-ffm`, `jline-reader`), the **FFM provider**, and run the JVM with `--enable-native-access=ALL-UNNAMED`. Everything below was checked against the JLine 4.7.0 sources and a probe run on this machine (OpenJDK 25 Zulu 25.28, Linux 6.18 WSL2, Maven 3.9.11), unless marked otherwise.

## Facts

### Version and Java 25 compatibility

- Latest JLine on Maven Central is **4.7.0** (released 2026-11-12). The 3.x line is still maintained (3.30.17, 2026-09-08), but new work is on 4.x. [S1] [S2]
- 4.0.0 changes that matter here: Java 11+ minimum, JPMS modules, **JNA and Jansi providers removed** (only FFM, JNI, exec remain), terminals throw after `close()`. [S3]
- The 4.0.0 notes say "Maven 4 requirement: POM model 4.1.0". That applies to building JLine only: the published consumer POMs use `modelVersion 4.0.0`, and Maven 3.9.11 resolves 4.7.0 fine (checked). [S3] [S4]
- `jline-terminal-ffm` is compiled with `java.release.version` 22; it runs on Java 25 (checked). [S4]

### Terminal providers on Java 25

Default lookup order is `ffm,jni,exec` (`TerminalBuilder.PROP_PROVIDERS_DEFAULT`); the first one that loads is used. Force one with `TerminalBuilder.provider("ffm")` or `-Dorg.jline.terminal.provider=ffm`. [S5]

| Provider | Artifact | Warning on Java 25 without flags (observed) | Fix |
|---|---|---|---|
| FFM | `jline-terminal-ffm` | `WARNING: java.lang.foreign.MemorySegment::reinterpret has been called by org.jline.terminal.impl.ffm.FfmTerminalProvider in an unnamed module ...` | `--enable-native-access=ALL-UNNAMED` |
| JNI | `jline-terminal-jni` + `jline-native` | `WARNING: java.lang.System::load has been called by org.jline.nativ.JLineNativeLoader in an unnamed module ...` | same flag |
| exec | in `jline-terminal` | none (forks `stty`) | none |
| dumb | in `jline-terminal` | none | none; no raw mode on a real TTY |

- Both native providers print the JEP 472 warning on JDK 24+; the JLine JPMS page says JNI needs "no permissions", which is out of date for JDK 24+. With `--enable-native-access=ALL-UNNAMED` both run silently (checked). [S6] [S7]
- JEP 472: default mode on JDK 24/25 is `--illegal-native-access=warn`; a future release makes `deny` the default, so the flag will become mandatory. [S7] JLine's FFM provider probes native access in its constructor and fails fast with a clear message if access is denied. [S8]
- On a single-module classpath app, the flag value is `ALL-UNNAMED`. `--enable-native-access=org.jline.terminal.ffm` works only when JLine is on the module path. [S6] Other ways to pass it (JEP 472): `JDK_JAVA_OPTIONS`, an `@argfile`, or `Enable-Native-Access: ALL-UNNAMED` in the manifest of a jar launched with `java -jar` (that is the only allowed manifest value). [S7]
- Recommendation: FFM, because it is the default, needs no bundled `.so`, and is the provider JLine is moving to. Keep `exec` as the fallback that needs no flag.

### Raw mode, and restoring the terminal

- `Attributes saved = terminal.enterRawMode()` clears `ICANON ECHO IEXTEN ISIG` and `IXON ICRNL INLCR`, sets `VMIN=1 VTIME=0`, and returns the previous attributes. It does **not** clear `OPOST`, so output `\n` still becomes CR LF. [S9]
- Consequences for the Console: Enter arrives as `0x0D` (ICRNL off); Ctrl-S/Ctrl-Q pass through (IXON off); Ctrl-V/Ctrl-O pass through (IEXTEN off); Ctrl-C/Ctrl-\/Ctrl-Z arrive as bytes `0x03/0x1C/0x1A` and do not kill or suspend the JVM (ISIG off). Observed: `0x03` reached the reader. So the Monitor must offer a quit command; Ctrl-C will not stop the Simulator.
- Restore: `terminal.setAttributes(saved)` then `terminal.close()`. `close()` on a POSIX terminal restores the attributes captured when the terminal was built (`pty.setAttr(originalAttributes)`). [S10]
- Crash: system terminals register a JLine shutdown hook that calls `close()` (`ShutdownHooks.add(closer)` in `PosixSysTerminal`). Checked: an uncaught exception in `main` while in raw mode left the pty in `icanon echo`. [S11] Shutdown hooks do not run on SIGKILL or a JVM crash (JDK `Runtime.addShutdownHook` contract); then the user needs `stty sane` or `reset`. No extra hook of our own is needed.

### Timed reads for a background reader thread

- `terminal.reader()` returns a `NonBlockingReader`: `read(long timeoutMs)` and `peek(long timeoutMs)` return a char, `EOF` (-1), or `READ_EXPIRED` (-2). [S12] [S13]
- `terminal.input()` is typed as plain `InputStream`; its Javadoc calls it a rare-case escape hatch. Read through `reader()` only, because `LineReader` also uses `reader()`, and mixing the two risks bytes being buffered in the decoder. [S13] For 7-bit ASCII the char is the byte.
- Pattern that worked: a daemon platform thread loops on `reader.read(100)`, skips `READ_EXPIRED`, and `put`s everything else into a `LinkedBlockingQueue`; the CPU thread calls `queue.poll()` (never blocks). The 100 ms timeout exists only so the thread can check a stop flag.

### Catching Ctrl-] and switching to the Monitor

- `0x1D` is not a default termios control char (`intr=^C quit=^\ lnext=^V` etc.), and with ISIG/IEXTEN off nothing in the kernel acts on it. Checked: it arrives as a plain char.
- JLine 4.1.4+ also does "software signals": with ISIG off it still calls the Java handler for `VINTR/VQUIT/VSUSP/VSTATUS` bytes, **and passes the byte through**. With the default `SIG_DFL` handler, `raise()` does nothing. So leave signal handlers alone; to switch this off entirely use `-Dorg.jline.terminal.softwareSignals=false`. [S5] [S9] [S14]
- Handover: the reader thread itself sees `0x1D`, enqueues it, then **parks** (for example on a `Semaphore`) so it stops reading. The CPU thread dequeues it, stops the CPU, runs the Monitor's `LineReader.readLine()` on the same `Terminal`, and on the Monitor's "continue" command releases the semaphore. Only one thread reads the terminal at a time. Checked: typed `help\r` reached `readLine`, and later bytes reached the Console again.
- `LineReader.readLine()` calls `enterRawMode()` itself and restores the attributes it found when it returns, so it works whether the terminal is in raw or cooked mode; no mode change is needed around the Monitor. [S16] It writes keypad and bracketed-paste sequences (`ESC[?1h ESC= ESC[?2004h`) while active (observed).
- In `readLine`, Ctrl-C throws `UserInterruptException` and Ctrl-D on an empty line (or EOF on a pipe) throws `EndOfFileException`. [S15]

### Startup terminal queries (gotcha)

- `TerminalBuilder.build()` auto-probes grapheme cluster mode and writes `ESC[?u ESC[?2026$p ESC[?2027$p ESC[?2048$p ESC[c` (observed), waiting up to `org.jline.terminal.probe.timeout` (default 200 ms) for replies. [S5] [S9] A late reply would land in the input as bytes the 6502 program sees.
- `TerminalBuilder.graphemeCluster(false)` (or `-Dorg.jline.terminal.graphemeCluster=false`) skips the probe; checked: no query bytes sent. Do that.

### stdin not a TTY

- `TerminalBuilder` builds a system terminal only if the provider reports stdin as a TTY; otherwise it falls back to a `DumbTerminal` on `System.in/out`. If `dumb` is unset and some stream is a TTY, it logs `Unable to create a system terminal, creating a dumb terminal`; `.dumb(true)` allows the fallback silently. [S5]
- Checked with `printf 'ab\035help\nq' | java ...`: `DumbTerminal`, type `dumb`; `enterRawMode()` is accepted, timed reads work, `0x1D` triggers the Monitor, `readLine` reads `help`, EOF arrives as -1. Bytes come through as-is because a dumb terminal over a pipe has no line discipline.
- `TerminalBuilder.builder().system(false).streams(in, out)` with in-memory streams is **not** a good test double: with FFM it opened a real pty (`PosixPtyTerminal`, plus the native-access warning) and then threw `ClosedException` on the first read once the input hit EOF (observed). `new DumbTerminal("test", "dumb", in, out, UTF_8)` (impl package) works with `ByteArrayInputStream` (checked).
- `mvn exec:exec` gives the child process pipes, so it would get a dumb terminal; JLine offers `.devTty(true)` / `-Dorg.jline.terminal.devtty=true` to open `/dev/tty` instead. [S5] `exec:java` runs inside Maven's own JVM and stdin. Simplest: launch with plain `java` (script or `java -jar`).

## Minimal API sketch

```java
// Maven: org.jline:jline-terminal, org.jline:jline-terminal-ffm, org.jline:jline-reader, all 4.7.0
// JVM:   --enable-native-access=ALL-UNNAMED

Terminal term = TerminalBuilder.builder()
        .system(true)
        .dumb(true)                 // silent fallback when stdin is a pipe
        .graphemeCluster(false)     // no startup probe bytes in the input
        .build();                   // FFM provider by default
Attributes cooked = term.enterRawMode();
NonBlockingReader in = term.reader();
BlockingQueue<Integer> rx = new LinkedBlockingQueue<>();
Semaphore resume = new Semaphore(0);

Thread.ofPlatform().daemon().name("console-reader").start(() -> {
    while (running) {
        int c = in.read(100);                          // char, EOF (-1) or READ_EXPIRED (-2)
        if (c == NonBlockingReader.READ_EXPIRED) continue;
        rx.put(c);
        if (c == NonBlockingReader.EOF) return;
        if (c == 0x1D) resume.acquire();               // Monitor owns the terminal now
    }
});

// CPU thread: Console Device read
Integer c = rx.poll();                                 // null = no key; never blocks
if (c != null && c == 0x1D) {
    LineReader monitor = LineReaderBuilder.builder().terminal(term).build(); // build once
    String cmd = monitor.readLine("> ");               // handles its own raw mode
    // ... on the "continue" command:
    resume.release();
}
// Console Device write
term.writer().print((char) b); term.flush();          // or term.output() for bytes

// exit
term.setAttributes(cooked);
term.close();                                          // shutdown hook also does this on crash
```

## Sources

- [S1] Maven Central metadata, `org.jline:jline`: https://repo1.maven.org/maven2/org/jline/jline/maven-metadata.xml
- [S2] JLine GitHub releases: https://github.com/jline/jline3/releases
- [S3] JLine 4.0.0 release notes: https://github.com/jline/jline3/releases/tag/4.0.0
- [S4] Published POMs: https://repo1.maven.org/maven2/org/jline/jline/4.7.0/jline-4.7.0.pom, https://repo1.maven.org/maven2/org/jline/jline-terminal-ffm/4.7.0/jline-terminal-ffm-4.7.0.pom
- [S5] `TerminalBuilder` (4.7.0): https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/TerminalBuilder.java
- [S6] JLine docs, Terminal Providers and JPMS: https://github.com/jline/jline3/blob/master/website/docs/modules/terminal-providers.md, https://github.com/jline/jline3/blob/master/website/docs/modules/jpms.md
- [S7] JEP 472, Prepare to Restrict the Use of JNI (delivered in JDK 24): https://openjdk.org/jeps/472
- [S8] `FfmTerminalProvider` (4.7.0): https://github.com/jline/jline3/blob/4.7.0/terminal-ffm/src/main/java/org/jline/terminal/impl/ffm/FfmTerminalProvider.java
- [S9] `AbstractTerminal` (4.7.0), `enterRawMode`, `raise`, probe timeout: https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/impl/AbstractTerminal.java
- [S10] `AbstractPosixTerminal` (4.7.0), `doClose`: https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/impl/AbstractPosixTerminal.java
- [S11] `PosixSysTerminal` (4.7.0), shutdown hook: https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/impl/PosixSysTerminal.java
- [S12] `NonBlockingReader` (4.7.0): https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/utils/NonBlockingReader.java
- [S13] `Terminal` interface (4.7.0), `reader()` / `input()`: https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/Terminal.java
- [S14] `SignalInterceptingInputStream` (4.7.0): https://github.com/jline/jline3/blob/4.7.0/terminal/src/main/java/org/jline/terminal/impl/SignalInterceptingInputStream.java
- [S15] `LineReader` interface (4.7.0), `readLine` exceptions: https://github.com/jline/jline3/blob/4.7.0/reader/src/main/java/org/jline/reader/LineReader.java
- [S16] `LineReaderImpl` (4.7.0), `readLine` raw mode save/restore: https://github.com/jline/jline3/blob/4.7.0/reader/src/main/java/org/jline/reader/impl/LineReaderImpl.java

Probe: a throwaway `Probe.java` run under `script(1)` (a real pty) with scripted keystrokes, and again with piped stdin. Not tested: an interactive Windows Terminal session on WSL2 (only `script` ptys), SIGTERM, and the JNI provider's bundled `.so` on other architectures.
