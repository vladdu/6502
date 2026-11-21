# 6502

A from-scratch 6502 toolchain and a fictional computer (the Machine) to run it on. The goal is to later host a small hand-written OS. Terms used here are defined in [GLOSSARY.md](GLOSSARY.md).

The toolchain is one command, `6502`, with three subcommands:

- `asm`: Assembler, source to a flat binary, listing and symbol file
- `disasm`: Disassembler, binary back to source that reassembles to the same bytes
- `run`: Simulator, runs a binary on the Machine, with an interactive Monitor (debugger)

The CPU is the NMOS 6502 with the documented instruction set, decimal mode and the original quirks (such as the `JMP ($xxFF)` page-wrap bug). Undocumented opcodes stop the Machine.

## Getting started

Requires JDK 25 and Maven.

```sh
mvn package          # builds target/6502.jar and runs the tests
bin/6502             # wrapper around java -jar target/6502.jar; no args prints usage
```

Add `-DskipTests` for a faster build.

### Hello world

Demos live in [demo/](demo/), one per subfolder, each with a `run.sh` that builds the jar if missing, assembles and runs:

```sh
demo/hello/run.sh             # prints Hello, 6502!
demo/hello/run.sh --monitor   # extra args go to `6502 run`
```

[demo/hello/hello.s](demo/hello/hello.s):

```asm
CON_DATA = $D000
EXIT     = $D300

        *= $0200
start:  ldx #0
@loop:  lda msg,x
        beq done
        sta CON_DATA
        inx
        bne @loop
done:   lda #0
        sta EXIT        ; stop the Simulator, exit code 0

msg:    .byte "Hello, 6502!", 10, 0
```

By hand:

```sh
bin/6502 asm demo/hello/hello.s       # writes hello.bin, hello.lst, hello.sym next to it
bin/6502 run demo/hello/hello.bin     # prints Hello, 6502!, exits 0
bin/6502 disasm demo/hello/hello.bin --syms demo/hello/hello.sym
```

## Assembler: `6502 asm SOURCE [-o BASE]`

Writes `BASE.bin` (flat binary from the lowest to the highest assembled address), `BASE.lst` (listing) and `BASE.sym` (symbol file). `BASE` defaults to `SOURCE` minus its extension. Errors are reported as `file:line:col: error: msg` with the source line and a caret; nothing is written then, exit code 1.

Full reference: [docs/assembler.md](docs/assembler.md).

Syntax:

- `*= ADDR` sets the origin and starts a new Section. Sections may leave gaps (filled with zeros) but must not overlap.
- `label:` defines a label. `@name:` is a cheap local label, valid between two ordinary labels.
- `NAME = expr` defines a constant.
- Numbers: `$FF` hex, `%1010` binary, `255` decimal, `'A'` character. Strings `"..."` with backslash escapes.
- Expressions: `+ - * / & | ^ << >>`, parentheses, `<expr` low byte, `>expr` high byte. Forward references are allowed, but arithmetic on a not-yet-defined symbol is limited to `sym +/- const` and `<`/`>` (see [ADR 0001](docs/adr/0001-assembler-sections-and-fixups.md)).
- Zero-page addressing is picked automatically when the operand is known and fits.
- Comments start with `;`.

Directives:

| Directive | Effect |
|---|---|
| `.byte expr, "str", ...` | bytes and strings |
| `.word expr, ...` | 16-bit little-endian words |
| `.res n[, fill]` | reserve `n` bytes (default fill 0) |
| `.align n[, fill]` | pad to a multiple of `n` |
| `.include "file"` | assemble another source file, path relative to the including file |
| `.incbin "file"` | insert a binary file's bytes |

## Disassembler: `6502 disasm IMG [--org ADDR] [--syms FILE]`

Prints source to stdout that reassembles to the same bytes. `--org` is the image's load address (default `$0200`). `--syms` takes a symbol file to name labels and constants and to show data bytes as `.byte`.

## Simulator: `6502 run [IMAGE] [options]`

Two ways to start:

- Program mode: `6502 run IMAGE [--org ADDR]` loads the binary at `ADDR` (default `$0200`) and starts there.
- ROM boot: `6502 run --rom IMG` puts the image (up to 8K) at the top of memory, ending at `$FFFF`, and starts from the reset vector.

Options:

| Option | Effect |
|---|---|
| `--rom IMG` | system ROM image, up to 8K, ending at `$FFFF` |
| `--char-rom IMG` | Character ROM image, up to 2K, at `$C800` |
| `--org ADDR` | Program mode load address and start PC (default `$0200`) |
| `--disk FILE` | attach a raw Disk image (Sector N at byte N*256, 1 to 65536 Sectors) |
| `--disk-ro` | attach the Disk read-only |
| `--syms FILE` | extra symbol file for the Monitor, repeatable. `IMAGE.sym` and `IMG.sym` next to the images load automatically |
| `--speed MHZ` | throttle to MHZ million cycles per second (e.g. `1`, `0.985`). Default unthrottled |
| `--monitor` | start paused in the Monitor (needs an interactive terminal, unless `--gui`) |
| `--gui` | run in the Front panel window instead of the terminal (needs a display) |

The program's stdin and stdout are the Console. Exit codes: the byte the program writes to the Exit device, 2 for usage errors, 125 for Simulator failures (load error, or a stop such as a breakpoint, illegal opcode or Stray interrupt outside an interactive terminal).

On an interactive terminal, press Ctrl-] to break into the Monitor (on a non-US layout, Ctrl plus the key where `]` is on a US keyboard, e.g. Ctrl+Å on Swedish). A stop other than an exit (illegal opcode, Stray interrupt) also drops into it.

### Monitor

| Command | Effect |
|---|---|
| `go [ADDR]`, `continue` | run (from ADDR) to the next stop |
| `step [N]`, `s [N]` | N Steps (default 1). An empty line repeats it |
| `next`, `n` | step over a JSR to its return. An empty line repeats it |
| `until ADDR` | run until PC reaches ADDR, or to any other stop first |
| `b ADDR`, `b del ADDR`, `b` | add, remove, list breakpoints |
| `w [r\|rw] ADDR [LEN]` | watch LEN bytes (default 1) for writes, `r` reads or `rw` both |
| `w del ADDR`, `w` | remove the Watchpoint starting at ADDR, list Watchpoints |
| `r [NAME=VALUE ...]` | show registers. Set `pc a x y s p` or a flag `n v d i z c` |
| `m [ADDR [LEN]]` | dump memory (default 128 bytes). Bare `m` continues |
| `m ADDR [LEN] = BB ...` | write bytes, repeated over LEN |
| `d [ADDR [N]]` | disassemble N instructions (default 16). Bare `d` continues |
| `load FILE [ADDR]` | copy a file to ADDR (default `$0200`), plus its `.sym` |
| `syms FILE` | merge a symbol file |
| `reset` | hardware Reset: Devices reset, PC from `$FFFC`, memory kept |
| `nmi` | press the NMI button: the next Step is the NMI entry |
| `disk [FILE [ro]]` | show the Disk image, or insert FILE (`ro`: write-protected) |
| `disk eject`, `disk ro`, `disk rw` | eject, or set or clear the write-protect tab |
| `restart` | start again as launched: RAM zeroed, images and symbols reread |
| `info` | Console, Timer, Disk, speed, loaded images and symbol files |
| `speed [MHZ \| off]` | show or set the throttle |
| `quit [CODE]`, `q [CODE]` | exit with CODE (default 0). Ctrl-D quits too |
| `help` | list commands |

Monitor numbers are hex (`$` optional), `#10` is decimal. Symbols work anywhere an address does: `sym`, `sym+N`, `sym-N`.

## The Machine

### Memory map

| Range | Contents |
|---|---|
| `$0000-$C7FF` | RAM (`$A000-$BFFF` and `$C000-$C7FF` reserved for future video) |
| `$C800-$CFFF` | Character ROM (RAM when no `--char-rom`) |
| `$D000-$D0FF` | Console |
| `$D100-$D1FF` | Timer |
| `$D200-$D2FF` | Disk |
| `$D300-$D3FF` | Exit device |
| `$D400-$DFFF` | unmapped, reserved for Devices |
| `$E000-$FFFF` | system ROM (RAM when no `--rom`) |

A ROM area with an image attached is read-only throughout, and bytes past the image read `$FF`. An interrupt (BRK, IRQ, NMI) whose vector is `$0000` stops the Machine as a Stray interrupt instead of jumping.

### Devices

Memory is mapped in whole 256-byte pages, so every Device takes at least one page; each current Device takes exactly one. Offsets are from the Device's base address. Offsets a Device doesn't use read `$FF` and ignore writes, like unmapped memory; nothing is mirrored.

**Console** (`$D000`): serial-style character I/O, no screen memory.

| Offset | Read | Write |
|---|---|---|
| `$00` | next input byte (0 if none) | send a byte |
| `$01` | status: bit 7 input waiting, bit 6 ready to send (always), bit 5 input closed (EOF) | |
| `$02` | control | bit 7: IRQ while input is waiting |

**Timer** (`$D100`): 16-bit countdown with reload that can raise IRQ, plus a 32-bit free-running cycle counter.

| Offset | Read | Write |
|---|---|---|
| `$00` | | latch the current count and cycle counter |
| `$01` | status: bit 7 expired flag, bit 6 running | clear the flag |
| `$02` | control | bit 7 IRQ enable, bit 1 periodic, bit 0 run |
| `$04-$05` | reload value, lo/hi (0 means 65536) | set reload |
| `$06-$07` | latched count, lo/hi | |
| `$08-$0B` | latched cycle counter, low byte first | |

**Disk** (`$D200`): 256-byte Sectors moved to and from a RAM page by DMA. A command completes 256 cycles after it is issued.

| Offset | Read | Write |
|---|---|---|
| `$00` | | command: `$01` read Sector into page, `$02` write page to Sector (ignored while busy) |
| `$01` | status: bit 7 done, bit 6 busy, bit 5 error, bit 4 no media, bit 3 read-only | clear done |
| `$02` | control | bit 7: IRQ on done |
| `$03` | error code of the last command | |
| `$04-$05` | Sector number, lo/hi | set Sector |
| `$06` | RAM page (high byte of the address) | set page |
| `$08-$09` | last Sector number, lo/hi | |

**Exit device** (`$D300`): writing a byte to offset `$00` stops the Simulator with that byte as the exit code.

## Tests

```sh
mvn test                  # unit tests, plus Klaus Dormann's functional/interrupt tests and Bruce Clark's decimal test
mvn verify -Psinglestep   # also the SingleStepTests suite (downloads ~620 MB on first run)
```

Test data is downloaded on first use and cached in `${XDG_CACHE_HOME:-~/.cache}/6502-tests`. The build runs Error Prone and SpotBugs with warnings as errors.
