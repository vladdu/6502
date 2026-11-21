# Assembler

`6502 asm` turns 6502 assembly source into a flat binary the Simulator can run. Terms are defined in [GLOSSARY.md](../GLOSSARY.md).

## Running it

```sh
6502 asm SOURCE [-o BASE]
```

It writes three files. `BASE` defaults to `SOURCE` without its extension.

| File | Contents |
|---|---|
| `BASE.bin` | the bytes from the lowest to the highest assembled address, with any gaps filled with zeros |
| `BASE.lst` | the listing: every source line with its address and bytes |
| `BASE.sym` | the symbol file, read by `6502 disasm --syms` and by the Monitor |

On any error, nothing is written and the exit code is 1.

The `.bin` file has no header, so the load address is not stored in it. Pass the lowest origin to the Simulator yourself. Program mode also starts executing at that address, so put the entry point first:

```sh
6502 asm prog.s                  # code starts with *= $0800
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

A label needs an origin (`*=`) before it.

**Cheap local labels** start with `@`. They belong to the most recent ordinary label, so every routine can reuse `@loop` and `@done`:

```asm
print:  ldy #0
@loop:  lda (ptr),y
        beq @done
        iny
        bne @loop
@done:  rts
```

Only labels open a new scope; constants don't. A cheap local label before any ordinary label is an error. The symbol file names cheap local labels `parent@name`, for example `print@loop`.

**Constants**: `NAME = expr`. The value must be known on that line, so a constant cannot refer to a symbol defined further down. A constant cannot be a cheap local label.

```asm
CON_DATA = $D000
ptr      = $FB
```

## Origin and Sections

`*= expr` sets the address where the following code goes, and starts a new Section. The value must be in `$0000-$FFFF` and known on that line. Code, data, labels and `*` all need an origin first.

There can be several `*=` lines. Sections may leave gaps between them, and the gaps are zeros in the `.bin`. Sections must not overlap, and code must not run past `$FFFF`.

A label on a `*=` line names the address before the origin moves. With `*= *+n`, that reserves `n` bytes without putting any in the `.bin`, which is how to give names to variables in zero page:

```asm
        *= $80
ptr:    *= *+2          ; ptr = $80, two bytes
count:  *= *+1          ; count = $82

        *= $0200
        lda #0
        sta count       ; zero page, count is known
        sta (ptr),y
```

A Section with no bytes adds nothing to the `.bin`, so this image is 6 bytes and starts at `$0200`. Writing `ptr: .res 2` instead would put two zero bytes at `$80`, and the `.bin` would start at `$80` with a gap of zeros up to `$0200`.

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

You can use a symbol before its definition in instruction operands and in `.byte`/`.word` items. The value is filled in once all the source has been read. Until then, the symbol can only appear in these forms:

- `sym`
- `sym + n`
- `sym - n`
- `n + sym`
- `<` or `>` applied to one of the forms above

Here `n` must be a number or a constant, not a label or `*`. Anything else, such as `.byte end - start` when `end` comes later, fails with `define end before this use`. To fix it, move the definition above the use.

These places need the value on the same line, with no forward references: `NAME =`, `*=`, and the arguments of `.res` and `.align`.

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

The assembler uses the 2-byte zero-page form when the operand's value is known on that line and lies in `$00-$FF`. Otherwise it uses the 3-byte absolute form. A symbol defined further down therefore always gets the absolute form, so define zero-page constants near the top.

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
| `.res n[, fill]` | `n` bytes of `fill` (default 0), written to the `.bin`; to reserve without bytes, see [Origin and Sections](#origin-and-sections) |
| `.align n[, fill]` | `fill` bytes (default 0) up to the next address that is a multiple of `n` |
| `.include "file"` | assemble another source file at this point |
| `.incbin "file"` | insert a file's raw bytes at this point |

File paths are relative to the file that contains the directive.

```asm
msg:    .byte "Hello", 10, 0
vecs:   .word nmi, reset, irq
buf:    .res 256
        .align 256, $EA
font:   .incbin "font.bin"
```

## Programs in several files

There is no linker. To split a program into files, use `.include`. The included file is assembled as if its text were pasted at that line:

```asm
        *= $0200
        jsr print
        brk
        .include "lib.s"    ; lib.s has no *=, so it follows straight on
```

- **One namespace.** The program and the included files share all their symbols. A duplicate name is an error, so use cheap local labels inside library routines.
- **Shared state.** An included file continues the current Section and the current cheap-local-label scope. If it has its own `*=`, its code goes at that address instead.
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

Each line shows the address, up to three bytes, and the source text. Lines with more bytes than that continue on extra lines without source. `.res`, `.align` and `.incbin` lines show `(N bytes)` instead of the bytes. A `; file:` line marks each change of source file. A line before the first `*=` has no address.

### Symbol file (`.sym`)

```
00FB ptr const local
0200 start label local
0235 print@loop label local
```

Each line has the value in hex, the name, `label` or `const`, and the Linkage. Every symbol is `local` for now. Lines are sorted by value, and symbols with the same value appear in definition order.

`6502 run` loads `IMAGE.sym` from next to the image automatically. `6502 disasm --syms FILE` uses it to name addresses.

## Errors

The assembler reports every error it finds in one run, not just the first:

```
err.s:5:13: error: value $12C does not fit in a byte
        lda #300
            ^
```

Errors on individual lines come first, in source order. Then come undefined symbols, then overlapping Sections.

## Not supported

- macros
- conditional assembly
- `.org` (use `*=`)
- undocumented opcodes
- separate assembly and linking, including `.export`/`.import` (see [ADR 0001](adr/0001-assembler-sections-and-fixups.md))
