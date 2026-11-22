# Threading techniques and sizes of NMOS 6502 Forths

Research for [#67](https://github.com/vladdu/6502/issues/67), part of map [#65](https://github.com/vladdu/6502/issues/65), input to [#68](https://github.com/vladdu/6502/issues/68). Checked 2026-11-22 against the commits and files listed under [Sources](#sources).

Every number is one of these:

- **measured**: read from a release binary, a listing or a source file by a throwaway script. Nothing downloaded was built or run.
- **counted**: summed from an instruction sequence with the cycles in this repo's [Opcodes.java][opcodes].
- **stated**: the author's claim, not checked.
- **estimated**: derived here, with the inputs shown.

Short names: STC is subroutine threading, DTC direct, ITC indirect. IP is the threaded-code pointer, W the pointer to the Word being entered, xt an execution token. "ROM" sizes are one contiguous area, with 6 bytes lost to the vectors. Where it goes is not decided here.

## Answer

**Cost per technique** (bytes by construction, cycles counted, typical path):

| Technique | Bytes per reference | Primitive dispatch | Nested call and return | Literal | `DOES>` child | `EXECUTE` | Needs code in RAM |
|---|--:|--:|--:|--:|--:|--:|---|
| STC, as durexForth | 3 | 12 | 12 (tail call 3) | 67 | 87 | 33 | no for the calls, its helpers patch themselves |
| STC, inline literal and branch (sketch) | 3 | 12 | 12 | 28 | 77 | 35 | no |
| DTC, pre-increment `NEXT`, PETTIL | 2, plus padding | 18 | 86 | 143 | 157 | 67 | yes, 7 bytes in zero page |
| DTC, the same with even Cells and a carry (sketch) | 2, plus padding | 21 | 98 | 60 | 130 | 44 | yes, 11 bytes in zero page |
| DTC, `NEXT` in ROM (sketch) | 2 | 41 | 129 | 83 | 173 | 64 | no |
| ITC, fig-Forth | 2 | 44 | 132 | 86 | 165 | 70 | one `JMP` opcode in zero page |
| ITC, volksFORTH | 2 | 35 | 152 | 84 | 213 | 73 | yes, `NEXT` in zero page |

**Predicted ROM** for all 133 Core Words, the map's extra Words, the Block Words, a Disk driver, boot, Warm start and autoload (estimated, method in section 5):

| Technique | Bytes | Middle | 8K (8,186) | 10K (10,234) | 12K (12,282) | 16K (16,378) |
|---|--:|--:|---|---|---|---|
| ITC | 7,400-9,200 | 8,300 | low end only | yes | yes, with margin | yes, with margin |
| DTC, `NEXT` in ROM | 7,500-9,300 | 8,400 | low end only | yes | yes, with margin | yes, with margin |
| DTC, pre-increment `NEXT` | 7,600-9,500 | 8,600 | low end only | yes | yes, with margin | yes, with margin |
| STC, size-conscious | 7,700-10,400 | 9,100 | low end only | most of the range, not the top | yes, with margin | yes, with margin |
| STC, speed first, mostly assembly | 10,000-14,300 | 12,000 | no | low end only | about half the range | yes |

"With margin" means the top of the range still fits after another 15%. The ranges are envelopes of stated assumptions, not confidence intervals.

- **Don't plan on 8K** for any technique.
- **10K** is likely enough for threaded code. For size-conscious STC it covers all but the top of the range, with no room for the budget to slip.
- **12K** holds all four size-conscious candidates.
- **16K** holds everything predicted here. No candidate needs more for this Word list. A full-featured system does: Tali Forth 2 with all options is 19,575 bytes (measured).
- Threading moves the total less than the implementation does. References are 42% of fig-Forth's bytes, and converting that kernel moves it by +1% to +5% for DTC and +7% to +13% for STC (estimated from measured counts). The uncertainty in the prediction is larger than that.

**Worth building in the prototype** (the choice is the prototype ticket's):

1. **STC.** With inline literals, branches and return-stack Words it is the fastest in every row counted (with helpers only, literals and `>R` lose to candidate 2), and the plainest inner model: no IP, no W, no `NEXT`, nothing in RAM. Its price is size and compiler special cases. Build it with plain runtime helpers and with inline literal and branch, as those two differ by more than 2 times on literals and 15 times on a branch.
2. **DTC with a pre-increment `NEXT` in zero page.** The only threaded form near STC on primitives (18-21 against 12 cycles). Its price is an alignment or padding rule in the compiler. Building it shows whether that rule is tolerable.
3. **DTC with `NEXT` in ROM.** The control: no alignment rule, no code in RAM, about 100 to 200 bytes smaller than candidate 2, about twice its dispatch cost.

ITC isn't worth a build. It needs the code-field wrap rule, and it saves about 1% against DTC. fig's form is slower than DTC in ROM on primitives. volksFORTH's faster `NEXT` needs code in zero page too, where candidate 2 is faster again.

**What real systems weigh** (measured):

| System | Bytes | What that is |
|---|--:|---|
| fig-Forth 6502, ITC, NMOS | 6,260 | whole pre-standard kernel, 86 Core names, host console and disk routines outside |
| volksFORTH C64, ITC, NMOS | 13,799, or 16,105 with Blocks | whole Forth-83 system, C64 drivers included, KERNAL outside |
| durexForth 5.0.0, STC, NMOS | about 7,000 | all Core names, cut out of a 15,437-byte image full of tools (461 of it estimated), KERNAL outside |
| Tali Forth 2, STC, 65C02 only | 19,575 | every option. Its Core Words alone are 3,866 of code and 1,298 of headers, without the routines under them |

**What each technique must handle:**

- All but volksFORTH keep the data stack in zero page under X and use page 1 as the return stack, so at most 128 nested addresses, less what loops, interrupts and helpers take.
- ITC: no code field at `$xxFF`. DTC jumping through IP: no thread Cell at `$xxFF`. DTC through W and STC avoid the `JMP ()` wrap with a fixed vector.
- STC: return-stack Words, inline data and tail calls are compiler special cases.
- Interrupts don't clear D, and Warm start must rebuild any code the technique keeps in RAM.
- `DOES>` patches the child in every system read. `EXECUTE` is a jump through a vector, or `RTI` in PETTIL.

## 1. Systems looked at

| System | Threading | CPU | Standard | Licence | Used for |
|---|---|---|---|---|---|
| [fig-Forth 6502 1.1][fig-pdf] (1980) | ITC | NMOS | fig, 86 of the Core names | public domain, notice must be kept ([cover][fig-pdf]) | cycles, exact byte split |
| [volksFORTH 3.9.x, C64][volks] | ITC | NMOS | Forth-83, 110 of the Core names | BSD-2-Clause ([LICENSE][volks-lic]) | cycles, sizes with and without Blocks |
| [PETTIL][pettil] | DTC | NMOS | Forth-83 | no licence file found: learn only | cycles, padding technique |
| [durexForth 5.0.0][durex] | STC | NMOS (6510) | Forth-2012 Core with `compat` loaded | MIT ([LICENSE][durex-lic]) | cycles, sizes |
| [Tali Forth 2][tali] | STC with inlining | **65C02 only** | ANS/2012, all 133 Core Words | public domain ([COPYING][tali-lic]) | sizes, inline technique |
| [AlexForth][alex] | DTC | 65C02 (`.pc02`) | own | GPL-3.0-only | shape of a W-based `NEXT` and colon entry only |

The Core-name counts and Tali's 133 are measured: the systems' Dictionaries against the [standard's list][core]. A name match doesn't prove the 2012 semantics.

Techniques only were taken from these. The short sequences quoted below explain cycle counts.

## 2. Techniques

### Bytes

| | ITC | DTC | STC |
|---|---|---|---|
| Reference | 2 | 2 | 3 (`JSR`) |
| Colon definition overhead | 4: code field, `EXIT` | 5: `JSR`/`JMP` enter, `EXIT` | 1: `RTS`, or 0 with a tail call |
| Primitive overhead | 5: code field, `JMP NEXT` | 3: `JMP NEXT` | 1: `RTS` |
| 16-bit literal | 4 | 4 | 5 by helper, 7 inline |
| 8-bit literal | 3 | 3, or 4 with even Cells | 4 by helper, 5 inline |
| Branch | 4 | 4, or 3 with PETTIL's byte offset | 5 by helper, 3 as `JMP` |
| `CREATE`/`DOES>` child before its data | 4 in fig, 2 in volksFORTH | 3 | 5 in durexForth, 3 in Tali |

By construction, from [fig][fig-src], [volksFORTH][volks-core], [PETTIL][pettil-inner], durexForth's [compiler][durex-compiler] and [control][durex-control], and [Tali][tali-main].

### Where the pointers and stacks live

| System | IP | W | Data stack | Return stack | Source |
|---|---|---|---|---|---|
| fig-Forth | zero page | zero page, with opcode `$6C` just below it | zero page `$20-$9E`, Cells side by side, X is the pointer | page 1, shared with an input buffer at `$0100` | [source][fig-src] |
| volksFORTH | operand of an `LDA` inside the zero-page `NEXT` | operand of that `NEXT`'s `JMP ()` | any RAM, through a 16-bit zero-page pointer | any RAM, the same way. Page 1 is free for machine code | [source][volks-core] |
| PETTIL | operand of the zero-page `JMP ()` | none | zero page, 47-48 Cells split into low and high bytes, X, top Cell cached apart | page 1 | [symbols][pettil-zp], [inner][pettil-inner] |
| AlexForth | zero page | zero page | zero page, side by side, X | page 1 | [source][alex] |
| durexForth | CPU PC, return addresses in page 1 | scratch in zero page | zero page `$03-$72`, 56 Cells split, X | page 1 | [main][durex-main], [memory map][durex-mem] |
| Tali | CPU PC, return addresses in page 1 | none | zero page down from `$78`, side by side, X, 64 Cells at most | page 1 | [manual][tali-manual] |

The stack layout is independent of threading. A split stack saves one `DEX` or `INX` (1 byte, 2 cycles) per push or pop. The prototype should use one layout for all candidates, or it measures the layout too.

### How `NEXT` and call/return work

- **fig ITC** ([source][fig-src]): `NEXT` copies the Cell at IP to W, adds 2 to IP, then goes through `JMP W-1` to a `JMP (W)` in zero page, which fetches the code field. 41 cycles, 44 with the primitive's `JMP NEXT`. The colon entry pushes IP and sets IP to W+2, `EXIT` pops IP. The listing's `NEXT` and colon entry also `JSR` to trace routines, which its own comment says to remove. They aren't counted.
- **volksFORTH ITC** ([source][volks-core]): `NEXT` is copied to zero page at cold and warm boot, and IP is patched into it. 32 cycles, 35 with the `JMP`. Every primitive must leave X=0 and Y=1. Both stacks go through pointers, so nesting costs more than in fig although dispatch is cheaper.
- **PETTIL DTC** ([symbols][pettil-zp], [inner][pettil-inner]): `NEXT` is `INC ip`, `INC ip`, `JMP (ip)` with IP as the operand: 5+5+5 = 15 cycles, as the author states, 18 with the `JMP`. The high byte is never incremented, so the compiler pads the thread and inserts a `PAGE` Word at each page boundary (21 cycles per crossing, counted). A colon Word starts `JSR enter`.
- **DTC with even Cells** (sketch, not found in a system, not run): the same `NEXT` plus `BNE` over an `INC ip+1`. An even low byte can only wrap on the second `INC`, so that one test is enough: 18 cycles, 22 on a carry. Threads must be even, so a colon xt ahead of its `JSR` is odd, byte operands become Cells and inline strings are padded.
- **DTC in ROM** (sketch, the shape of [AlexForth][alex] with NMOS instructions): fig's `NEXT` ending in `JMP (W)`. 38 cycles, 41 with the `JMP`. W still holds the xt, so colon entry needs only a `JMP`.
- **STC** ([durexForth][durex-compiler]): a reference is `JSR xt` (6) and the return `RTS` (6). A last call compiled as `JMP` costs 3 extra cycles in place of 12.

### Cycles

Cost means the cycles added by inserting that item in a running definition, its own dispatch included. So a primitive row is pure overhead, "nested" is an empty colon Word, `DOES>` is a child with an empty action and `EXECUTE` runs an empty primitive. Branch targets inside the code are assumed not to cross a page.

| Item | fig ITC | volks ITC | DTC in ROM | DTC even Cells | PETTIL | durexForth | STC inline |
|---|--:|--:|--:|--:|--:|--:|--:|
| `NEXT` alone | 41 | 32 | 38 | 18 | 15 | none | none |
| Primitive | 44 | 35 | 41 | 21 | 18 | 12 | 12 |
| Primitive, IP carry and page cross | 49 | 45 | 46 | 25 | 18, and 21 more per `PAGE` | 12 | 12 |
| Nested call and return | 132 | 152 | 129 | 98 | 86 | 12 | 12 |
| Nested, worst `NEXT` both times | 142 | 172 | 139 | 106 | 86, and 21 more per `PAGE` | 12 | 12 |
| Literal, 16-bit | 86 | 84 | 83 | 60 | 143 | 67 | 28 (26 split stack) |
| Unconditional branch | 75 | 66 | 72 | 48 | 48 | 45 (`REPEAT` compiles a `JMP`: 3) | 3 |
| `0BRANCH`, not taken / taken | 71 / 90 | 84 / 100 | 68 / 87 | 48 / 63 | 65 / 72 | 38 / 58 | 27 / 29 |
| `DOES>` child | 165 | 213 | 173 | 130 | 157 | 87 | 77 |
| `EXECUTE` | 70 | 73 | 64 | 44 | 67 | 33 | 35 |
| `>R` | 62 | 87 | 59 | 39 | 55 | 49 | 18 inline |

What was added up, beyond the sequences above:

- **Nested, threaded:** `NEXT`, colon entry, `NEXT`, `EXIT`. fig: 41 + 33 + 41 + 17 = 132. DTC in ROM: 38 + 36 + 38 + 17 = 129. Even Cells: 18 + 45 + 18 + 17 = 98. PETTIL's entry and exit jump into `NEXT` after its increments, which is why it isn't 15 twice.
- **DTC sketches:** colon entry saves IP with `LDA`/`PHA` twice. In ROM it then adds 3 to W. With even Cells it pulls the `JSR` address and decrements it once. `EXIT` is two `PLA`/`STA` pairs and `JMP NEXT`.
- **STC sketches:** a literal is `LDA #`, `LDY #`, `JSR push` (push is `DEX`, `DEX`, two stores, `RTS`), the sequence [Tali][tali-main] uses and legal on NMOS. A conditional branch is `JSR` to a pop-and-test, `BNE` over a `JMP`. `>R` is the six instructions inline. `DOES>` is Tali's two `JSR`s, with two NMOS `PLA` pairs for the addresses.
- **DTC sketches, `DOES>`:** a `JMP` (in ROM) or `JSR` (even Cells) to an action that starts `JSR dodoes`. The data address comes from W, or from the child's return address.
- **volksFORTH's worst case** counts the `NEXT` carry and page cross only. A borrow in its stack pointers adds more.
- **PETTIL's literal and `EXECUTE`** pay for its cached top Cell and shared subroutines. They aren't the floor for its threading, as the sketch column shows.

For scale: a 16-bit `+` on side-by-side Cells is 30 cycles of work (counted). With dispatch that is 42 in STC, 51 in DTC with even Cells, 71 in DTC in ROM and 74 in fig ITC, so 1.2 to 1.8 times. Nesting is where they part: 8 to 11 times.

### `DOES>`

- **fig** ([source][fig-src]): `<BUILDS` reserves a second Cell after the code field. `DOES>` stores the thread's address there. The shared runtime pushes IP, loads it from that Cell and pushes the data address W+4.
- **volksFORTH** ([source][volks-core]): the child's code field points at a `JMP` compiled inside the defining Word. The runtime finds the thread 3 bytes past that `JMP` and the data at W+2.
- **PETTIL** ([inner][pettil-inner]): the child starts with a `JSR` to the action, which starts `JSR dodoes`. The two return addresses give the data and the thread.
- **durexForth** ([base][durex-base], [compiler][durex-compiler]): the child is `JSR dodoes`, then a pointer to the action (an `RTS` until `DOES>` patches it), then the data. The runtime pushes the data address and jumps.
- **Tali** ([source][tali-main]): the child is `JSR push_pfa`. `DOES>` patches its operand to point at `JSR dodoes` in front of the action, so there are two return addresses as in PETTIL. The 3-byte child needs no reserved Cell.

All five patch a child after `CREATE`. The child is in RAM for anything defined at run time. A child built into ROM has to be assembled already patched.

### `EXECUTE`

- **fig and volksFORTH:** pop the xt to W and enter the same `JMP (W)` as `NEXT`. The xt is a code-field address.
- **DTC:** pop to a fixed zero-page Cell and `JMP ()` through it. A W-based colon entry needs that Cell to be W.
- **PETTIL:** push the xt and P, then `RTI`, which jumps to the exact address, where `RTS` adds 1 ([inner][pettil-inner]).
- **durexForth:** pop to a fixed zero-page Cell and `JMP ()`. The target's `RTS` returns to the caller ([interpreter][durex-interp]).

### Other threading

- **STC with inlining and tail calls** is the serious variant. Tali copies short Words inline (default limit 20 bytes) and flags those that must or must never be inlined ([manual][tali-manual], [definitions][tali-defs]). durexForth turns a last `JSR` into `JMP` ([control][durex-control]). Both are compiler policies on STC, not other threading.
- **`RTS` as `NEXT`** (S as IP) follows from the opcodes but isn't viable here: S reaches only page 1, so threads couldn't be in ROM, and an interrupt would overwrite them.
- **Token threading and milliForth's minimal-indirect threading:** not examined. I found no comparable NMOS Core system for the first, and [milliForth][milli] (GPL-2.0) is a minimal system without Core, 624 bytes (stated).

## 3. Measured sizes

### fig-Forth 6502: 6,260 bytes

`$0200-$1A73`, measured from the [original listing][fig-pdf]. A size count over the [text of that listing][fig-zip] matches seven listed addresses, the last being `TOP = $1A74`.

| Part | Bytes |
|---|--:|
| Boot parameters and the gap before the first Word | 36 |
| Name and link fields, 220 Words | 1,560 |
| Code fields | 440 |
| Machine code | 1,324 |
| Compiled references, 1,281 of them | 2,562 |
| Inline operands and strings | 286 |
| Constant, variable and user bodies | 52 |
| **Total** | **6,260** |
| of which trace code and its two calls | 155 |

It includes the compiler, text interpreter, compiler security, vocabularies, a Block layer, `LIST` and `INDEX`. It has no editor, assembler or embedded source. Console and disk go through the host monitor, the buffers aren't in the figure. It lacks 47 of the Core names, 6 of them present under older names. No 65C02 instructions.

### volksFORTH C64: 13,799 bytes, 16,105 with Blocks

Measured from the committed binaries [v4th-c64][volks-bin] and [v4thblk-c64][volks-blk], 2-byte load address removed, split at header addresses.

| Part | Bytes |
|---|--:|
| Forth-83 core, with user-area image, vocabularies, heap, `FORGET`, `SAVE` | 11,394 |
| C64 console and serial bus | 1,461 |
| File `INCLUDE` | 629 |
| Final helpers and assembler constants | 315 |
| **Without Blocks** | **13,799** |
| Block buffers, `LOAD`, `LIST`, `COPY`, `CONVEY` | 1,625 |
| 1541 sector driver | 681 |
| **With Blocks** | **16,105** |

Headers are 3,926 of the 13,799 (402 Words, each header with a Block number, a link and the name). The 110 Core-named Words take 2,521 bytes plus 920 of headers, hidden helpers that follow them included. Uses the C64 KERNAL. No 65C02 instructions are needed.

This is the best evidence that ITC on NMOS reaches today's standard: with a [source shim][volks-shim] for the missing names, its committed [test report][volks-golden] shows zero Core and Block errors. I didn't run it, and the shim's compiled size isn't in either figure (not measured).

### durexForth 5.0.0: 15,437 bytes as shipped, 4,937 of assembly kernel

Measured from `DUREXFORTH` in the [release disk][durex-d64], split with its own Dictionary at the end of the assembly kernel. The release file is not a kernel: it was saved after compiling its Forth source.

| Part | Code and data | Headers |
|---|--:|--:|
| Assembly kernel, 115 Words | 4,132 | 805 |
| Compiled Forth, 232 Words (assembler, `v` editor, tools, rest of Core) | 8,696 | 1,771 |
| Save padding and Dictionary terminator | 33 | |

The assembly kernel alone has 76 Core Words and is not Core. The subset that matters:

| Part | Bytes | Kind |
|---|--:|---|
| Assembly kernel less `disk.asm` | 4,487 | measured |
| Compiled Core support: base, `DO`/`LOOP`, `ACCEPT`, `:NONAME`, `.S` | 2,033 | measured |
| **119 Core Words and support** | **6,520** | measured |
| The 14 Core Words only `compat.fs` supplies, with helpers and headers | 461 | estimated by counting its [source][durex-compat] as STC |
| **All 133 Core names** | **about 7,000** | |

It needs the C64 KERNAL for character I/O and line input and has no Block Words. Assembler, editor, file loading and modules are outside the 7,000. No 65C02 instructions. The release build runs the Core tests after loading `compat` ([test][durex-test], [Makefile][durex-make]). I didn't rerun them. The 16,480-byte `.crt` is a padded cartridge container, not a size.

### Tali Forth 2: 19,575 bytes, 65C02

Measured from the committed [32K binary][tali-bin].

| Part | Bytes |
|---|--:|
| Forth, `$8000-$CC09` | 19,466 |
| py65 I/O kernel | 103 |
| Vectors | 6 |
| **Used** | **19,575** |
| Padding | 13,193 |
| **File** | **32,768** |

Inside the 19,466 (measured from the code lengths in its headers, overlapping bodies counted once):

| Part | Code | Headers |
|---|--:|--:|
| 133 Core Words | 3,866 | 1,298 |
| The other 7 Block Words | 227 | 83 |
| The map's 11 extra Words (12 headers, one name is in two word lists) | 298 | 117 |
| All other Words | 5,142 | 3,678 |
| Outside any Word's stated length: shared routines, tables, strings | 4,757 | |

- Embedded Forth source is 224 bytes of splash text (measured). It is evaluated at boot.
- The binary has every option: two editors, assembler, disassembler, word lists, a RAM disk.
- Stated only: "a little under 12K" for the [minimal platform][tali-min], about 24K for all options, about 1.4K for the Block option ([platform notes][tali-plat]). The option figures don't add up to the measured total, so I didn't subtract them.
- Core bodies include underflow checks, 3 bytes and 16 cycles each (stated, [manual][tali-manual]).
- It does not assemble for NMOS (stated, [platform][tali-min]). The source, all options and conditional parts included, uses 133 `BRA`, 123 `STZ`, 24 accumulator `INC`/`DEC`, 70 X/Y push or pull, 24 `TRB`/`TSB`, 49 `(zp)` operands, 2 `JMP (abs,X)` and 2 `BIT #` (measured). At 0-1, 0-2, 1-2, 1-3, 2-4, 0-2, 4-8 and 1-2 extra bytes each, NMOS costs about 150-850 bytes, 1-4% (estimated).

### PETTIL: not measured

The author states "lightweight runtime core, under 6K" ([README][pettil]). That core excludes the interpreter, compiler, editor and assembler, which are PETTIL studio, so it can't be compared with the others. I found no binary I could split.

## 4. NMOS pitfalls

| Pitfall | ITC | DTC | STC |
|---|---|---|---|
| `JMP ($xxFF)` reads its high byte from `$xx00` ([Cpu.java][cpu]) | No code field may start at `$xxFF`. fig allots a byte in `CREATE` and shifts the kernel ([source][fig-src]), volksFORTH has `6502-ALIGN` ([source][volks-core]). ROM Words need the check at assembly time | With W: none, W is a fixed Cell. With IP as the vector: no thread Cell may start at `$xxFF`, so even Cells or PETTIL's [padding][pettil-page] | Only `EXECUTE` and other vectors. A fixed vector off `$xxFF` is immune |
| 256-byte hardware stack | fig nests 2 bytes a level and puts its input buffer in the same page. volksFORTH moves the return stack out, at 20 cycles a nesting level over fig | 2 bytes a level | 2 bytes a level, plus every helper `JSR`, with loop parameters and `>R` between the return addresses |
| `>R`, `R>`, `R@`, `UNLOOP`, loop runtimes | plain pushes and pulls | plain pushes and pulls | A called `>R` must lift its own return address: 49 cycles against 18 inline. durexForth exempts them from tail calls ([core][durex-core]), Tali strips the juggling when inlining ([manual][tali-manual]) |
| Inline data after a call | IP points at it | IP points at it, odd lengths need padding in the pre-increment form | The helper fixes the return address, which is the last byte of the `JSR`, not the next one. Or compile real instructions |
| Tail calls | none | none | Unsafe when the last Word reads its return address, when a forward branch targets the final `RTS`, or when an immediate Word compiled something other than a final `JSR`. durexForth exempts all immediate Words ([interpreter][durex-interp]) |
| Code in RAM | fig writes `$6C` below W at cold start, and Warm start must too | A zero-page `NEXT` is copied at ROM boot and again at Warm start, as it is in reach of wild stores | None required. durexForth as written can't run from ROM: 8 instructions use a patched placeholder and 31 labels point into operands ([main][durex-main], measured in the source) |
| Page crossing | `(IP),Y` costs 1 more, the IP carry 4 in fig | the same, or padding | none for `JSR`/`RTS` |

For all of them:

- 128 nested addresses is the absolute limit of page 1. The text interpreter's own nesting, loop parameters (4 bytes a loop in fig and durexForth), helpers and an interrupt entry (3 bytes, plus the handler) come out of it.
- X wraps, and so does `zp,X`. A data stack overflow in zero page silently overwrites other variables before the prompt's check sees it, so the check needs slack on both sides. A 16-bit pointer stored at `$FF` takes its high byte from `$00`.
- Interrupt entry doesn't clear D ([Cpu.java][cpu]). Handlers and Warm start need `CLD`. fig does it at cold and warm start.
- The NMI button can land mid-`NEXT` or mid-`JSR`, so Warm start can trust neither S, X, IP, W nor a patched operand.
- STC compiles special cases where threading compiles a Cell. `POSTPONE`, `COMPILE,`, `RECURSE` and `>BODY` all have to know the instruction shapes.

## 5. Prediction for this OS

Scope: 133 Core Words, the 11 extra Words on the map, the 7 Block Words Core doesn't have, one Word to store a line, a Disk driver for four Sectors, ROM boot, Warm start and autoload. About 152 Words. One Block buffer. RAM variables, stacks and buffers don't count. Two top-down brackets from real NMOS systems set the ranges, a bottom-up count checks them.

### From durexForth: STC, 7,700-9,100

| Step | Bytes | Kind |
|---|--:|---|
| All Core names and their support | 6,980 | measured, plus 461 estimated |
| Extra Words it lacks in that subset | +100 | estimated from its Word sizes |
| Block Words, line Word, Disk driver | +500 to +900 | estimated: fig's Block layer is about 480 measured, Tali's 7 Words 310 measured before its helpers, volksFORTH's multi-buffer layer 1,625 and driver 681 measured |
| ROM boot, RAM init, Warm start, handlers, autoload | +300 to +600 | estimated, no system measured has the same job |
| Remove self-modification | +100 to +400 | estimated from about 40 patched sites |
| Full conformance and the map's recovery rules | +100 to +300 | estimated |
| Drop C64 screen, files and KERNAL housekeeping | -400 to -200 | estimated |
| **Total** | **7,680-9,080** | |

### From fig-Forth: ITC, 7,400-9,200

| Step | Bytes | Kind |
|---|--:|---|
| Kernel without trace code | 6,105 | measured |
| Drop vocabularies, `FORGET`, `LIST`, `INDEX`, `MON`, monitor disk I/O and other unneeded Words | -863 | measured spans |
| 41 missing Core Words (6 more are renames) | +1,947 | Tali's measured code for exactly those, 1,543, plus 322 of headers and 82 of code fields |
| NMOS replacement in those | 0 to +70 | estimated at 0-4% |
| Overlap with the fig Words they replace | -600 to 0 | estimated |
| 9 of the extra Words (it has `HEX`, and `VLIST` for `WORDS`) | +200 to +300 | estimated |
| 2012 semantics: loops, `DOES>` on `CREATE`, input nesting | +200 to +500 | estimated |
| Recovery rules | 0 to +200 | estimated |
| Disk driver, line Word, Block fixes | +200 to +400 | estimated |
| ROM boot, Warm start, autoload | +200 to +500 | estimated |
| **Total** | **7,389-9,159** | |

### From ITC to the other techniques

The same 6,105-byte kernel, converted on paper from measured counts (137 colon Words, 44 code Words, 33 data Words, 6 shared code fields, 1,281 references of which 131 are `EXIT`):

| Change | DTC in ROM | DTC even Cells | STC |
|---|--:|--:|--:|
| Colon code fields | +137 | +137 | -274 |
| Code Words lose their code field | -88 | -88 | -88 |
| Data Words and shared code fields | +39 | +39 | +39 |
| References | 0 | 0 | +1,150, and -131 for `EXIT` |
| Byte literals become Cells, colon alignment, `NEXT` copy | 0 | +80 to +217 | 0 |
| `JMP NEXT` to `RTS`, tail calls | 0 | 0 | -213 to 0 |
| Runtime routines | 0 | 0 | -30 to +70 |
| **Kernel** | **6,193 (+1%)** | **6,273-6,410 (+3% to +5%)** | **6,558-6,871 (+7% to +13%)** |

- DTC in ROM is the ITC bracket plus about 100: 7,500-9,300.
- DTC pre-increment is the ITC bracket plus about 200-300: 7,600-9,500. PETTIL's padding should land there too, but wasn't measured.
- STC is the ITC bracket times 1.07-1.13, 7,900-10,400. Joined with durexForth's 7,700-9,100 that gives 7,700-10,400.

### Bottom-up check

- Headers (2 link, 1 length, the name) for the 152 Words: about 1,040 (counted from the standard's names).
- Bodies: fig averages 20.5 bytes a Word with shared runtimes, Tali's Core 29, durexForth's assembly kernel 36 with the interpreter (all measured). 152 Words at 20.5-36 give 3,100-5,500.
- 40-70 headerless helpers at 20-30 bytes: 800-2,100 (estimated).
- Boot, driver and messages: 550-1,200 (estimated).
- Total 5,500-9,800 for threaded code, 5,900-11,100 scaled to STC. Wider, and it contains both brackets' middles.

### Speed-first STC: 10,000-14,300

The risk case is an OS written as Tali is, nearly all assembly with checks and inlining. The top is Tali's stated minimal 12K, plus its stated 1.4K Block option and 4% for NMOS: about 14,300. That includes string, double and tool Words this OS doesn't need, and our boot and driver replace its platform kernel. The bottom, 10,000, is a judgement: about where the size-conscious range ends. This row is the least certain.

### What could move these

- Both brackets lean on systems that use a host ROM for input and output, and neither reaches this OS's conformance as it stands.
- Arithmetic speed-ups aren't included. A table-driven multiply could add hundreds of bytes or more.
- The estimated rows add up to a quarter or more of each total. The prototype measures references, headers and runtimes, but not those rows.
- The choice of what is assembly and what is colon code moves the total as much as threading does, and shrinks threading's share when more is assembly.

## Not checked

- Nothing was assembled or run: not the sketches, the cycle paths or any system's test suite.
- Sizes not measured: PETTIL, Tali's minimal build, the compiled volksFORTH shim, durexForth master (5.0.0 was measured).
- Not examined: Acornsoft Forth (a disassembly of a copyrighted ROM), AlexForth's size, ChronoForth (a durexForth derivative), 2E Forth OS, tcforth, eight-bit-forth (8-bit Cells), commercial systems.
- The exact obligations of [#66](https://github.com/vladdu/6502/issues/66). Its Word list may move the estimates.

## Sources

| Source | Version read | SHA-256 of the file measured |
|---|---|---|
| fig-Forth listing, `fig-forth_6502.pdf` | Release 1.1, September 1980 | `16d26de0bb82bd216de58aa31af23fbad7f1c883480d53213d02a9f133e2b2bd` |
| fig-Forth listing as text, `FIG6502.ASM` in `forth65.zip` | typed copy of the same release | `3c2e71023b2a2f372a4cd1aef5859cdc6f6cf2d2d582696a354960442c310c0d` |
| volksFORTH, `v4th-c64` | commit `9fbb463e88837eac53fadf8b7748d0170819ff4c` | `6cd9d356e205ee955b30d60302ea854059ec8e8d9335ae471153ea3bf217eb60` |
| volksFORTH, `v4thblk-c64` | the same | `0a1bfc3c9bad9bf134e7e6e9c2ca1ba801271d8ed2de8bf711a79d0d3422b5a2` |
| durexForth, release `.d64` | v5.0.0, commit `0e28ffcbf1d4cd64d51363fe98d37f0d7400a2f8` | `3d28eec7429dab8a272fe6e7f05e85cf997098e426bfe066bb4eeb6b9e1ed39e` |
| Tali Forth 2, `taliforth-py65mon.bin` | commit `68079b0341d809d3f2bbf38fce1f70e934bf6731` | `bccec44e47bcbc9d133c212370fecb163aa1ddfccf69d0db33dc8358f88c19a8` |
| milliForth-6502 | commit `38a2290591b456c7b7228dd19b21876d9bc20391` | README only |
| PETTIL | commit `723a046e2a673a1c0c8c6741f424c369e7a03e17` | source only |
| AlexForth | commit `a3c991c37f982b94df58ade792fa440949a5e5c7` | source only |

[opcodes]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/cpu/Opcodes.java
[cpu]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/cpu/Cpu.java#L188-L240
[core]: https://forth-standard.org/standard/core
[fig-pdf]: https://dwheeler.com/6502/fig-forth_6502.pdf
[fig-zip]: https://dwheeler.com/6502/forth65.zip
[fig-src]: https://dwheeler.com/6502/forth65.zip
[volks]: https://github.com/forth-ev/VolksForth/tree/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64
[volks-lic]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/LICENSE
[volks-core]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64/src/vf-cbm-core.fth
[volks-bin]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64/cbmfiles/v4th-c64
[volks-blk]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64/cbmfiles/v4thblk-c64
[volks-shim]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64/tests/ans-shim.fth
[volks-golden]: https://github.com/forth-ev/VolksForth/blob/9fbb463e88837eac53fadf8b7748d0170819ff4c/6502/C64/tests/golden/report-blk.golden
[pettil]: https://github.com/chitselb/pettil/tree/723a046e2a673a1c0c8c6741f424c369e7a03e17
[pettil-zp]: https://github.com/chitselb/pettil/blob/723a046e2a673a1c0c8c6741f424c369e7a03e17/src/common/pettil.i65
[pettil-inner]: https://github.com/chitselb/pettil/blob/723a046e2a673a1c0c8c6741f424c369e7a03e17/src/core/core-inner.a65
[pettil-page]: https://github.com/chitselb/pettil/blob/723a046e2a673a1c0c8c6741f424c369e7a03e17/src/common/page.i65
[alex]: https://github.com/adumont/hb6502/blob/a3c991c37f982b94df58ade792fa440949a5e5c7/forth/forth.s
[milli]: https://github.com/agsb/milliForth-6502/blob/38a2290591b456c7b7228dd19b21876d9bc20391/README.md
[durex]: https://github.com/jkotlinski/durexforth/tree/v5.0.0
[durex-lic]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/LICENSE.txt
[durex-d64]: https://github.com/jkotlinski/durexforth/releases/download/v5.0.0/durexforth-v5_0_0.d64
[durex-main]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/asm/durexforth.asm
[durex-mem]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/manual/memmap.adoc
[durex-core]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/asm/core.asm
[durex-compiler]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/asm/compiler.asm
[durex-control]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/asm/control.asm
[durex-interp]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/asm/interpreter.asm
[durex-base]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/forth/base.fs
[durex-compat]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/forth/compat.fs
[durex-test]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/test/test.fs
[durex-make]: https://github.com/jkotlinski/durexforth/blob/v5.0.0/Makefile
[tali]: https://github.com/SamCoVT/TaliForth2/tree/68079b0341d809d3f2bbf38fce1f70e934bf6731
[tali-lic]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/COPYING.txt
[tali-main]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/taliforth.asm
[tali-defs]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/definitions.asm
[tali-manual]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/docs/manual.md
[tali-bin]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/taliforth-py65mon.bin
[tali-min]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/platform/minimal/platform.asm
[tali-plat]: https://github.com/SamCoVT/TaliForth2/blob/68079b0341d809d3f2bbf38fce1f70e934bf6731/platform/README.md
