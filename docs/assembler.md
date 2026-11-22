# Assembler

`6502 asm` turns 6502 assembly source into a flat binary the Simulator can run. Terms are defined in [GLOSSARY.md](../GLOSSARY.md).

## Running it

```sh
6502 asm SOURCE [-o BASE]
```

It writes three files. `BASE` defaults to `SOURCE` without its extension.

| File | Contents |
|---|---|
| `BASE.bin` | the bytes from the lowest to the highest assembled address outside [Reserved Sections](#reserved-sections), with any gaps filled with zeros |
| `BASE.lst` | the listing: every source line with its address and bytes |
| `BASE.sym` | the symbol file, read by `6502 disasm --syms` and by the Monitor |

On any error, nothing is written and the exit code is 1.

The `.bin` file has no header, so the load address is not stored in it. Pass its first address to the Simulator yourself: the lowest origin of the Sections that put bytes in it. Program mode also starts executing at that address, so put the entry point first:

```sh
6502 asm prog.s                  # with .defsection code, $0800
6502 run prog.bin --org '$0800'
```

`--org` defaults to `$0200`, which is the easiest origin to use.

## Source lines

Each line has this shape, and every part is optional:

```
label:  mnemonic operand    ; comment
```

- A comment runs from `;` to the end of the line.
- Spaces and tabs separate tokens. Indentation does not matter.
- Mnemonics, register names (`A`, `X`, `Y`), the `z:`/`a:` prefixes and directives are case-insensitive. Symbol names are case-sensitive: `Ptr` and `ptr` are different symbols.
- Source files are UTF-8. LF and CRLF line endings both work.

## Symbols

A name starts with an ASCII letter or `_`, followed by letters, digits or `_`. Each symbol can be defined only once.

**Labels** name the current address. The colon is required (`loop nop` is read as an unknown instruction `loop`). There is one label per line, either alone or before a statement:

```asm
start:  ldx #0
table:
        .byte 1, 2, 3
```

A label needs a `.section` before it, and can't be on a `.section` or `.defsection` line: put it on the next line.

**Cheap local labels** start with `@`. They belong to the most recent ordinary label of the same Section and source file, so every routine can reuse `@loop` and `@done`:

```asm
print:  ldy #0
@loop:  lda (ptr),y
        beq @done
        iny
        bne @loop
@done:  rts
```

Only labels open a new scope; constants don't. Each Section keeps its own scope, so a switch to data and back doesn't end the scope of a routine. An included file starts with no scope in any Section, and the including file has its scopes back afterwards. A cheap local label before any ordinary label of its Section and file is an error.

The symbol file names cheap local labels `parent@name`, for example `print@loop`. The Disassembler scopes by address, so it doesn't show such a name when an included file's label lies between the parent and the cheap local label.

**Constants**: `NAME = expr`. The value must be known on that line, so a constant cannot refer to a symbol defined further down. A constant cannot be a cheap local label.

```asm
CON_DATA = $D000
ptr      = $FB
```

## Sections

A program declares each Section once, with a name and an origin, and can then add to it from several places:

```asm
        .defsection code, $0200
        .defsection data, $0280

        .section code
start:  ldx #0
        .section data
msg:    .byte "hi", 0
        .section code           ; continues at $0202
        lda msg,x
```

- `.defsection NAME, ORIGIN[, reserved]` declares a Section and does not switch to it. `ORIGIN` must be in `$0000-$FFFF` and known on that line. Declaring a name twice is an error, also with the same origin.
- `.section NAME` switches to a declared Section and continues where that Section left off. The declaration must come first.
- An included file can add to any Section by name, see [Programs in several files](#programs-in-several-files).
- Code, data, labels and `*` need a `.section` first. Declarations alone are not enough. Constants need no Section.
- Section names follow the rules for symbol names, without `@`, and are apart from symbols: a Section and a label can both be called `code`.
- Sections may leave gaps between them, and the gaps are zeros in the `.bin`. Sections must not overlap, and code must not run past `$FFFF`.

An origin can follow the code before it, as in `.defsection data, code_end`. Any code added to `code` afterwards overlaps `data` once `data` has bytes. That is reported at the `.defsection` of the higher Section:

```
Section data ($0208-$0209) overlaps Section code ($0200-$0209)
```

A Section with no bytes never overlaps and adds nothing to the `.bin`.

### Reserved Sections

Variables and buffers go in a Section declared with `reserved` after its origin. They get addresses and names, and put no bytes in the `.bin`:

```asm
        .defsection zp,   $10, reserved
        .defsection code, $0200

        .section zp
ptr:    .res 2          ; ptr = $10, two bytes
count:  .res 1          ; count = $12

        .section code
        lda #0
        sta count       ; zero page, count is known
        sta (ptr),y
```

This image is 6 bytes and starts at `$0200`. Without `reserved`, it would start at `$10` with zeros up to `$0200`. A library declares its variables the same way, next to its code: `.section zp` and `.res` above the code that uses them, then `.section code` again. The program declares the Sections.

- A Reserved Section can hold labels, constants, `.res n`, `.align n`, `.include`, `.section` and `.defsection`.
- Instructions, `.byte`, `.word`, `.incbin` and a fill value for `.res` or `.align` are errors there, because they would never be loaded. Set the variables' first values in code.
- With an origin below `$100` it must end by `$FF`, so that variables can't run into the stack. The line that crosses `$FF` is the error. The last byte may be at `$FF`, and a label after it is `$100`. Sections that aren't reserved have no such bound.
- It overlaps like any other Section, so variables can't share memory with code, data or other variables unnoticed.
- `reserved` is case-insensitive.

## Numbers, characters and strings

| Form | Meaning |
|---|---|
| `255` | decimal |
| `$FF` | hexadecimal |
| `%11111111` | binary |
| `'A'` | one ASCII character, its code as a number |
| `"text"` | a string, allowed only as an item of `.byte` |

Strings take these escapes: `\n`, `\r`, `\t`, `\0`, `\\`, `\"`, `\'`, `\xHH`. Non-ASCII characters are stored as their UTF-8 bytes (`"é"` is `$C3 $A9`).

## Expressions

Operators, from lowest to highest precedence. All binary operators are left-associative:

| Operators | Meaning |
|---|---|
| `\|` | bitwise or |
| `^` | bitwise xor |
| `&` | bitwise and |
| `<<` `>>` | shift left, arithmetic shift right |
| `+` `-` | add, subtract |
| `*` `/` `%` | multiply, divide (truncates toward zero), remainder |
| `-x` `~x` | negate, bitwise not |
| `( )` | grouping |

The assembler does arithmetic on 32-bit signed integers. It checks the size of a result only when it stores it:

- a byte field takes `-128..$FF`
- a word field takes `-32768..$FFFF`

Negative values are stored as two's complement, so `lda #-1` gives `$FF`.

A few tokens depend on context:

- `*` on its own is the current address: the start of the instruction, or of the current item in a `.byte`/`.word` list. After a value, `*` means multiply.
- `%` before digits is a binary number. After a value, `%` means remainder.
- `<expr` gives the low byte and `>expr` gives the high byte of the whole expression after it. Both can only appear at the start of an operand or data item:

```asm
        lda #<msg       ; low byte of msg
        lda #>msg+1     ; high byte of (msg+1)
        .byte <vec, >vec
```

### Forward references

You can use a symbol before its definition in instruction operands and in `.byte`/`.word` items. The value is filled in once all the source has been read. A direct operand that then turns out to be in zero page is an error if the instruction has a zero-page form, see [Zero page or absolute](#zero-page-or-absolute). Until the value is known, the symbol can only appear in these forms:

- `sym`
- `sym + n`
- `sym - n`
- `n + sym`
- `<` or `>` applied to one of the forms above

Here `n` must be a number or a constant, not a label or `*`. Anything else, such as `.byte end - start` when `end` comes later, fails with `define end before this use`. To fix it, move the definition above the use.

These places need the value on the same line, with no forward references: `NAME =`, the origin of `.defsection`, and the arguments of `.res` and `.align`.

## Instructions

The assembler accepts the 56 documented NMOS 6502 instructions. Undocumented opcodes are not accepted.

| Mode | Syntax | Example |
|---|---|---|
| implied | `op` | `rts` |
| accumulator | `op` or `op A` | `asl`, `asl a` |
| immediate | `op #expr` | `lda #$20` |
| zero page / absolute | `op expr` | `lda count` |
| indexed | `op expr,X` / `op expr,Y` | `sta buf,x` |
| indirect | `op (expr)` | `jmp (vector)` |
| indexed indirect | `op (expr,X)` | `lda (ptr,x)` |
| indirect indexed | `op (expr),Y` | `lda (ptr),y` |
| relative | `op target` | `bne loop` |

### Zero page or absolute

The assembler uses the 2-byte zero-page form when the operand's value is known on that line and lies in `$00-$FF`. Otherwise it uses the 3-byte absolute form. The size of an instruction never changes after that.

So if an operand uses a symbol defined further down and turns out to be in `$00-$FF`, that is an error wherever the instruction has a zero-page form:

```
prog.s:3:13: error: operand $10 is in zero page: define ptr before this use, or write z: or a:
        sta ptr
            ^
```

Define the symbol above its use, or choose the form yourself with a prefix. This holds for labels, constants and aliases, and for `<sym` and `>sym` as an address, which are always in zero page. It does not apply to immediates, data items, branches, indirect operands, or instructions without a zero-page form such as `jmp sym` and `lda sym,y`. Forward references to `$100` and above are absolute.

A few instructions have only a zero-page form for one index, for example `stx zp,y` and `sty zp,x`. For these, a known value uses zero page and must fit in a byte. A value that is still unknown gets an error such as `STX has no absolute,Y mode`.

To override the choice, add a prefix:

```asm
        lda z:later     ; zero page, even though later is defined further down
        lda a:ptr       ; absolute, even though ptr is $FB
```

### Indirect or grouping

When an operand starts with `(`, the assembler treats it as indirect if the matching `)` ends the operand or is followed by `,Y`. Otherwise the parentheses group an expression: `lda (k+1)*2` loads from the address `(k+1)*2`.

So `lda (base+1)` is the indirect form. LDA has no plain indirect mode, so it is an error. To load from the address `base+1`, write `lda base+1`, or `lda 0+(base+1)` if you need the parentheses.

### Branches

A branch takes a plain target address. The target must be within -128..+127 bytes of the next instruction. Branches can wrap across `$FFFF`. A branch cannot take `z:`, `a:` or an index.

## Directives

| Directive | Effect |
|---|---|
| `.byte item, ...` | one byte per expression, the bytes of each string |
| `.word expr, ...` | 16-bit words, low byte first |
| `.res n[, fill]` | `n` bytes of `fill` (default 0), written to the `.bin`; in a [Reserved Section](#reserved-sections), only `n` addresses and no `fill` |
| `.align n[, fill]` | `fill` bytes (default 0) up to the next address that is a multiple of `n`; in a Reserved Section, only the addresses and no `fill` |
| `.include "file"` | assemble another source file at this point |
| `.incbin "file"` | insert a file's raw bytes at this point |
| `.defsection name, origin[, reserved]` | declare a [Section](#sections), without switching to it |
| `.section name` | continue a declared Section where it left off |

File paths are relative to the file that contains the directive.

```asm
msg:    .byte "Hello", 10, 0
vecs:   .word nmi, reset, irq
buf:    .res 256
        .align 256, $EA
font:   .incbin "font.bin"
```

## Programs in several files

There is no linker. To split a program into files, use `.include`. The included file is assembled at that line, and can add to any Section of the program:

```asm
; main.s
        .defsection code, $0200
        .defsection data, $0280

        .section code
        jsr first
        brk
        .include "lib.s"
next:   rts                 ; in code again, after the code of lib.s
```

```asm
; lib.s
first:  ldx #0              ; starts in code, the Section of the .include line
        .section data
msg:    .byte "hi", 0
        .section code
        lda msg,x
        rts
```

- **One namespace.** The program and the included files share all their symbols. A duplicate name is an error, so use cheap local labels inside library routines.
- **Sections.** An included file starts in the Section of the `.include` line. Afterwards the including file is back in that Section, or in none if it had none, whatever the included file switched to with `.section`. The Sections it declared and the bytes it added stay.
- **Cheap local labels.** An included file has its own [scopes](#symbols): it can't use the `@` labels of the file that includes it, and an include inside a routine doesn't break that routine's `@` labels.
- **Machine definitions.** `6502 defs > machine.inc` writes the Machine's addresses, Device registers and bits as constants, ready for `.include "machine.inc"`.
- **Include each file once.** A file that includes itself, directly or through other files, is reported as an error. Including the same file twice from different places defines its labels twice, which is also an error.

## Output files

### Listing (`.lst`)

```
0200  A9 1E     start:  lda #<msg
021E  68 69 0A  msg:    .byte "hi\n", 0
0221  00
0226  (10 bytes)          .align 16, $EA
; file: lib.s
0233  A0 00     print:  ldy #0
```

Each line shows the address, up to three bytes, and the source text. Lines with more bytes than that continue on extra lines without source. `.res`, `.align` and `.incbin` lines show `(N bytes)` instead of the bytes, also in a Reserved Section. A `; file:` line marks each change of source file. A `.section` line shows the address where its Section continues. A line before the first `.section` has no address.

### Symbol file (`.sym`)

```
00FB ptr const local
0200 start label local
0235 print@loop label local
data 0210 0016
```

Each symbol line has the value in hex, the name, `label` or `const`, and the Linkage. Every symbol is `local` for now. Symbol lines are sorted by value, and symbols with the same value appear in definition order.

After the symbols, a `data` line gives the start and length in hex of each run of bytes from `.byte`, `.word`, `.res`, `.align` and `.incbin`. Adjacent runs merge into one line. The disassembler shows these bytes as `.byte` lines, with runs of printable characters as strings, instead of decoding them as instructions. Readers skip lines of a kind they don't know.

Labels in a Reserved Section are listed like the others, but its memory gets no `data` line, so code that a program loads into a reserved buffer is disassembled as code.

`6502 run` loads `IMAGE.sym` from next to the image automatically. `6502 disasm --syms FILE` uses it to name addresses and to show data as data.

## Errors

The assembler reports every error it finds in one run, not just the first:

```
err.s:5:13: error: value $12C does not fit in a byte
        lda #300
            ^
```

Errors on individual lines come first, in source order. Then come errors in forward references, such as undefined symbols and operands in zero page, then overlapping Sections.

## Not supported

- macros
- conditional assembly
- `*=` and `.org`: declare a Section with `.defsection` and switch to it with `.section` (see [ADR 0002](adr/0002-named-sections-have-fixed-origins.md)). `*` on its own is still the current address.
- undocumented opcodes
- separate assembly and linking, including `.export`/`.import` (see [ADR 0001](adr/0001-assembler-sections-and-fixups.md))
