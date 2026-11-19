# CPU test suites: sourcing and build integration

Research for issue #2. Checked 2026-11-19 against the upstream repos via the GitHub API and by downloading sample files to a scratch directory (nothing was added to this repo).

## Recommendation

- **SingleStepTests (Tom Harte), NMOS 6502 set**: do not commit it and do not use a submodule. Download at test time from `raw.githubusercontent.com`, pinned to a commit SHA, one file per opcode, only the 151 documented opcodes, into a cache directory outside `target/` and ignored by git. `mvn clean` must not trigger another 618 MB download. Skip the tests (JUnit `Assumptions`) when the data is missing and the network is not available.
- **Dormann functional test**: download the prebuilt 64 KiB `.bin` the same way (pinned SHA plus a SHA-256 check). It is GPL-3.0, so downloading it means this repo never redistributes it. Committing it would also work (64 KiB), but then the repo carries a GPL file and has to keep its licence notice.
- **Dormann decimal test**: no prebuilt binary exists upstream, so it has to be assembled. The simplest route is to assemble it once (AS65 is bundled upstream, and a ca65 port exists) and commit the small binary with its provenance. The code is Bruce Clark's and is marked public domain. Later, assembling the unmodified source with this project's own assembler would make a good end-to-end acceptance test for #9.

## 1. SingleStepTests: NMOS 6502

### Source and licence
- Canonical repo: https://github.com/SingleStepTests/65x02. The data lives in directory `6502/v1/`. Sibling directories `nes6502`, `rockwell65c02`, `synertek65c02` and `wdc65c02` hold other CPUs and are not needed here.
- The old repo https://github.com/SingleStepTests/ProcessorTests (formerly `TomHarte/ProcessorTests`, which now redirects there) is archived, and its README says: "archived, having been subdivided by test set. See https://github.com/orgs/SingleStepTests/". It has no licence file (the GitHub API reports `license: null`). Do not use it.
- Licence of `65x02`: MIT, "Copyright (c) 2024 Thomas Harte et al" (`LICENSE` at the repo root).
- Last commit touching `6502/v1`: `b7ed82824258d999aeb233324f6ea60c6cc4570a` (2024-07-27, "Correct JAM/KIL/HLT bus activity"), which affects only undocumented JAM opcodes. Repo HEAD on `main`: `2f6980a2d95757486c7bee24355c360e40e2a224` (2025-05-10). Pin downloads to one of these.
- The README says sets are "manually versioned to permit for potential future breaking changes in JSON format" (that is the `v1` directory).

### Layout and size
- 256 files, `00.json` to `ff.json`, named by opcode in lower-case hex. Each file holds 10,000 tests (stated in `6502/README.md`, and confirmed by counting `ea.json`, `69.json`, `bd.json`, `00.json` and `02.json`).
- Plain JSON (`file`: "JSON text data"). No Git LFS: the repo has no `.gitattributes`, and the raw URLs serve the full content.
- Total for all 256 files: **1,081,886,574 bytes (about 1.08 GB)**. Per file: 3,235,274 to 5,462,079 bytes.
- Documented opcodes only (151 files): **618,153,223 bytes (about 618 MB)**.
- Compression on one sample (`69.json`, 3,289,048 bytes): gzip -9 gives 564,272 (17%) and xz -9 gives 387,412 (12%). Gzipped, the documented set would be roughly 100 MB.
- Raw URL pattern: `https://raw.githubusercontent.com/SingleStepTests/65x02/<sha>/6502/v1/<op>.json`.

### JSON format (from `6502/README.md`, checked against downloaded files)
Each file is a JSON array of objects:
- `name`: string (opcode bytes), ignorable.
- `initial` and `final`: `pc`, `s`, `a`, `x`, `y`, `p` (integers), plus `ram`, a list of `[address, value]` pairs.
- `cycles`: a list of `[address, value, "read" | "write"]`, one entry per cycle.

README rules:
- "All tests assume the entire 64KiB memory space is RAM."
- "Any memory address not included in a test's `ram` lists must not be accessed during that test."
- Procedure: load `initial`, run **one instruction**, then compare against `final`.

Observed in the samples (not stated in the README):
- `p` always has bit 5 set and bit 4 (B) clear in both `initial` and `final` for documented opcodes (checked in `00`, `69` and `bd`). Store P with bit 5 = 1 and B = 0, or mask before comparing.
- BRK (`00.json`) pushes P with bits 4 and 5 set in every test.
- About 50% of tests in every sampled file start with D set (for example, 4,962 of 10,000 in `69.json` ADC #imm). That means ADC and SBC decimal behaviour, including invalid BCD and the NMOS N/V/Z results, is covered by random operands.
- Cycle counts vary where they should. For example, `bd.json` (LDA abs,X) has 4 cycles in 5,045 tests and 5 in 4,955 (page crossing).

### Using it with an instruction-stepped CPU
The README states this case directly: "If you aren't emulating individual cycles (skipped step #3), you can still count how many elements are contained in the `cycles` list and compare with the number of cycles your emulator should have taken to perform the instruction."

So for each test:
1. Load registers and `initial.ram` into a 64 KiB RAM.
2. Run one instruction, and get back the number of cycles it took.
3. Assert registers equal `final`, every `final.ram` pair matches memory, and `cycles.size()` equals the returned cycle count.

You can ignore addresses, values and read/write types in `cycles`. Dummy reads and writes (for example, the page-cross dummy read in `bd`) appear there, but an instruction-stepped CPU does not have to reproduce them.

Optional cheap extra check: have the test bus record the addresses it touches and assert each one appears in `initial.ram` or `cycles`. That catches stray reads and writes without cycle stepping.

## 2. Klaus Dormann functional and decimal tests

### Source and licence
- Canonical repo: https://github.com/Klaus2m5/6502_65C02_functional_tests (default branch `master`, HEAD `7954e2dbb49c469ea286070bf46cdd71aeb29e4b`, 2020-01-05).
- Licence: GPL-3.0 (`license.txt`, and the header of `6502_functional_test.a65` says "GPLv3 or later", "Copyright (C) 2012-2020 Klaus Dormann").
- `6502_decimal_test.a65` header: "Written by Bruce Clark. This code is public domain." The `readme.txt` says Dormann added configuration options.
- Files: `6502_functional_test.a65` (148,497 B), `6502_decimal_test.a65` (9,186 B), `6502_interrupt_test.a65` (needs an external IRQ/NMI feedback register), `65C02_extended_opcodes_test.a65c` (not relevant here), `as65_142.zip` (the assembler), and `bin_files/`.
- `bin_files/` contains `6502_functional_test.bin` (65,536 B, SHA-256 `fa12bfc761e6f9057e4cc01a665a7b800ff01ae91f598af1e39a1201d01953fd`), `6502_functional_test.lst` (728,468 B) and the 65C02 equivalents. **There is no decimal-test binary.**
- Assembler: AS65 1.42 by Frank A. Kingswood. The zip contains a Linux i386 ELF (`as65`), `as65.exe` and `AS65-DOS.EXE`. I did not run it. The readme says: "I will not switch to another assembler. However, GitHub user amb5l has a CA65 compatible version". That is https://github.com/amb5l/6502_65C02_functional_tests (a GPL-3.0 fork with `ca65/6502_decimal_test.ca65`, `ca65/6502_functional_test.ca65` and a Makefile).

### Functional test: prebuilt binary facts (checked in `.lst` and `.bin`)
- Configuration of the prebuilt binary: `ROM_vectors = 1`, `load_data_direct = 1`, `I_flag = 3`, `zero_page = $0A`, `data_segment = $0200`, `code_segment = $0400`, `disable_decimal = 0`, `report = 0`.
- Load: the whole 64 KiB image at address `$0000`, with every address writable RAM.
- Start: set PC to **`$0400`** (`start cld`; byte `d8` at offset `$400`). The reset vector does *not* point to start. `$FFFA/$FFFC/$FFFE` = `nmi_trap $379D`, `res_trap $37A3`, `irq_trap $37AB`. The IRQ vector is needed because BRK is tested.
- Pass/fail: no I/O. Every check is a trap that loops on itself (`jmp *`, `beq *`, `bne *` and so on), and success is `jmp *` as well. Stop when PC does not change after an instruction. **Pass if that PC is `$3469`** (listing: `3469 : 4c6934 jmp * ;test passed, no errors`; the binary holds `4c 69 34` at `$3469`). Any other PC is a failure, and you look that address up in the `.lst` to find which test failed.
- Scope (from the source header): "Tests documented behavior of the original NMOS 6502 only! No unofficial opcodes." Decimal ADC/SBC is tested "only ... with valid BCD operands and N V Z flags will be ignored". That is why the separate decimal test matters.

### Decimal test: configuration and signalling (from `6502_decimal_test.a65`)
- Defaults: `cputype = 0` (6502), `vld_bcd = 0` (allow invalid BCD), `chk_a = 1`, `chk_c = 1`, `chk_n = 0`, `chk_v = 0`, `chk_z = 0`.
- For NMOS: keep `cputype = 0` and `vld_bcd = 0`. The defaults check only A and C. With `cputype = 0`, the source assembles NMOS-specific predictors (`A6502` and `S6502`) for N, V and Z, so setting `chk_n`, `chk_v` and `chk_z` to 1 tests full NMOS flag behaviour. SingleStepTests already covers these flags with random operands, so that is belt and braces.
- Memory: variables at zero page from `org 0`. `ERROR` is at **`$000B`** (N1, N2, HA, HNVZC, DA, DNVZC, AR, NF, VF, ZF, CF, ERROR in order; the source comments mark `$04`, `$08`, `$0C`). Code at `org $200`, entry `TEST` = **`$0200`**.
- Result: `ERROR = 0` means pass and `1` means fail.
- End: the `end_of_test` macro emits **`db $db`** (the 65C02 STP instruction). On NMOS, `$DB` is an undocumented opcode, so a documented-only CPU cannot execute it. Either stop the harness when the opcode at PC is `$DB`, or redefine the macro to `jmp *` before assembling. Stopping on `$DB` keeps the upstream source unmodified.
- Runtime: the source says "approximately 1 minute at 1 MHz".

## 3. Getting the data into a Maven build

GitHub limits (from docs.github.com, "About large files on GitHub"): a warning above 50 MiB per file, a hard block above 100 MiB, and repositories "ideally less than 1 GB".

| Option | SingleStepTests (618 MB documented, 1.08 GB all) | Dormann functional `.bin` (64 KiB) | Decimal `.bin` (must be built) |
|---|---|---|---|
| Commit to repo | No. 618 MB of history in a learning repo, slow clones. Gzipped it is still about 100 MB. A small committed sample (first N tests per opcode) is possible for offline smoke tests, but it is extra work. | Possible. The repo then carries a GPL-3.0 file and its notice. | Yes. It is small and the code is public domain; record the source SHA and config. |
| Download at build/test time | Yes. Lazy per-opcode fetch pinned to a SHA, cached outside `target/`. The first run costs about 618 MB once. | Yes, same mechanism plus a SHA-256 check. | Not possible: no upstream binary. |
| Git submodule | No. `SingleStepTests/65x02` is about 1 GB packed (GitHub API `size` 997,358 KB) and includes the other four CPUs. Submodules have no per-path checkout. | Overkill for one file, though the repo is small (517 KB). | Would still need AS65 or ca65 in the build. |

Implementation notes for the download option (simplest first):
- Use `java.net.http.HttpClient` from the test code itself, through a JUnit 5 `@TestFactory` that makes one dynamic test per opcode file and fetches it on first use. This needs no Maven plugin. A Maven plugin such as download-maven-plugin would fetch everything up front and is less flexible.
- Cache directory: something like `${user.home}/.cache/6502-tests/<sha>/` or a git-ignored `.cache/` at the project root. Do not use `target/`.
- The JDK 25 standard library has no JSON parser. You need either a test-scoped dependency (Jackson or Gson) or a small hand-written parser. The format is only arrays, integers and three string values.
- Tag these tests (for example `@Tag("singlestep")`) so a fast unit-test run can exclude them.

## Sources
- https://github.com/SingleStepTests/65x02 (`README.md`, `LICENSE`, `6502/README.md`, `6502/v1/*.json`)
- https://github.com/SingleStepTests/ProcessorTests (archived README)
- https://github.com/Klaus2m5/6502_65C02_functional_tests (`readme.txt`, `license.txt`, `6502_functional_test.a65`, `6502_decimal_test.a65`, `bin_files/6502_functional_test.lst`, `bin_files/6502_functional_test.bin`, `as65_142.zip`)
- https://github.com/amb5l/6502_65C02_functional_tests (`ca65/` port)
- https://docs.github.com/en/repositories/working-with-files/managing-large-files/about-large-files-on-github
