# Forth-2012 Core obligations and test files

Research for [#66](https://github.com/vladdu/6502/issues/66), part of map [#65](https://github.com/vladdu/6502/issues/65). Checked 2026-11-22 against the [Forth-2012 document][pdf] and the test suite at commit [`4c87e565`][pin].

How to read the sources: numbers such as [3.4.1] and [6.1.1360] are sections of the standard. References such as [core:795] are lines at the pinned commit (`prelim` = `prelimtest.fth`, `tester` = `tester.fr`, `core` = `core.fr`, `plus` = `coreplustest.fth`). "Scan" means a throwaway Python script that tokenised the files as data, following their parsing Words. "By reading" means worked out from the source. **No test file was run**, and no Forth was used.

## Answer

1. **Core is 133 Words**, all required, plus the rules of chapter 3 and the documentation of 4.1. A Standard System must document 35 implementation-defined options, its action on each ambiguous condition, and six further facts ([5.1.1], [4.1.1], [4.1.2], [4.1.3]).
2. **Run order is `prelimtest.fth`, `tester.fr`, `core.fr`, `coreplustest.fth`**, as four streams of lines into one session ([runner:9-14]). That is 1,613 lines and 49,621 bytes.
3. **The Words the OS must add for that run are `.(`, `NIP`, `TUCK` and `:NONAME`.** The prelim test defines `\`, `TRUE`, `FALSE` and `HEX` when they are missing. Without the prelim test, add `\`, `HEX` and `FALSE`. `TRUE` is not needed by the other three files.
4. **Zero errors does not mean a pass.** An unknown Word, an abort, a hang or an early end of input adds nothing to either counter. The prelim test counts in `#ERRS` and the tester in `#ERRORS`: two variables, and neither sets an exit code.
5. **A harness needs three things:** both counters at zero, no interpreter error, and proof that the end was reached that an echo of the source can't fake. Options are in section 6.
6. **A system built to the bare minimums fails the tests.** They need a `WORD` buffer for at least 49 characters (the minimum is 33) and lower-case hex digits (only a "should" in the standard). They also use the whole 80-character input line, so a line terminator must not take one of the 80.
7. **The line is the unit of input.** The files rescan with `>IN`, jump to fixed columns, skip to end of line through `SOURCE >IN !` and parse an empty rest of line. Feed them byte for byte as lines. Copied into Blocks, they mean something else ([7.3.3]).
8. **Five Core Words are never used:** `KEY`, `QUIT`, `ABORT`, `ABORT"` and `ENVIRONMENT?`. The output Words and `ACCEPT` run, but only a person looking at the output could see them fail. More gaps are in section 7.
9. **The standard sets no minimum stack depth.** It requires the sizes to be documented ([3.2.3], [4.1.3]).
10. **Licences differ by file.** Two allow free distribution with the notice kept, one is public domain, and `prelimtest.fth` has no notice at all.

## "Known so far", checked

| Line in the ticket | Verdict | What holds |
|---|---|---|
| The suite was at `4c87e565` | Confirmed, repo moved | Still the head of `master` on 2026-11-22. `gerryjackson/forth2012-test-suite` now redirects to [Forth-Standard/forth-standard-test-suite][repo] ([readme]). |
| `\` (all files) | Corrected | True of three files. `prelimtest.fth` uses `\` only after defining it if absent ([prelim:188]). |
| `HEX` and `FALSE` (`tester.fr`) | Confirmed | [tester:15], [tester:20]. The prelim test provides both if absent ([prelim:196-198]). |
| `.(` and `HEX` (`core.fr`) | Confirmed, incomplete | [core:1007], [core:17]. It also needs `\`, including through `POSTPONE \` ([core:420]). |
| `.(`, `TRUE`, `NIP`, `TUCK`, `:NONAME` (`coreplustest.fth`) | Corrected | `TRUE` is wrong: its only appearance is inside a `."` string ([plus:274]). The other four are right, and so is `\`. |
| `tester.fr` counts in `#ERRORS` and prints them. No exit code | Corrected in part | It counts ([tester:26-34]) and prints each failure's message and current line. It never prints the count. No exit code is right. |
| The `ACCEPT` test at the end of `core.fr` consumes a line | Confirmed, sharpened | It is near the end ([core:998]), one test before the last group. With the file piped, it consumes the blank line [core:999]. |
| `core.fr` doesn't test `KEY`, `QUIT`, `ABORT`, `ABORT"`, `ENVIRONMENT?` | Confirmed | It says so ([core:12-13]), and the scan finds none of the five used in any of the four files. |

## 1. Sources and pin

- Standard: [forth-2012.pdf][pdf], the document both [forth-standard.org][site] and [forth200x.org][committee] link as Forth-2012. Its title page is dated 10 November 2014 and still says "draft proposed Standard". SHA-256 `d824d5a0919c555bab7996898bfa15c3d40f6da51f7621f18354a993f102fe05`. The text was read from the PDF. Word spellings come from forth-standard.org's list, because text extraction loses `*`. Section links go to forth-standard.org, which adds user contributions below the standard's text.
- Suite: commit `4c87e5659c5b79bd7a6664daa56603df080c6f9b` (2026-09-05), one README-only commit after tag `v0.15.0`. The four files are the same blobs at `v0.13.6`, `v0.15.0` and the pin.

| File | Lines | Bytes | Longest line | SHA-256 |
|---|--:|--:|--:|---|
| [`prelimtest.fth`][prelim] | 233 | 9,555 | 79 | `4af07906ed9e9bec92dc9854e02fb7d9f9daf125e4adf91467ce78a0d71fd737` |
| [`tester.fr`][tester] | 66 | 2,225 | 77 | `12a34cca4f6041b9c79bb4259e7a66af84bcbbfa2ad728a47bf050cd5396f6f7` |
| [`core.fr`][core] | 1,009 | 27,293 | 78 | `9beba157c1929f2908199b2b13a5c2a349df2d3dcc79a293e13080efcc860f54` |
| [`coreplustest.fth`][plus] | 305 | 10,548 | 80 | `d967d1d367b3c0bf91b13944694fb5ac26fa793a63be3ea58bf50779971ef47b` |

Line lengths exclude the LF. All four are ASCII with LF endings, no CR, no tab, no other control character, and a final newline (scan). The downloaded files' git blob hashes match the commit's tree.

## 2. The 133 Core Words

Computed from the [list of 6.1][wordlist]. The 133 section numbers match both the PDF's glossary and its alphabetic list. `c` marks the 29 Words whose interpretation semantics are undefined. Everything else must work in interpretation state ([3.4.3.2]).

```
Number is the last part of 6.1.nnnn.

0010   !            0380   2DUP         0860   C,           1670   HOLD         2165 c S"
0030   #            0400   2OVER        0870   C@           1680 c I            2170   S>D
0040   #>           0430   2SWAP        0880   CELL+        1700 c IF           2210   SIGN
0050   #S           0450   :            0890   CELLS        1710   IMMEDIATE    2214   SM/REM
0070   '            0460 c ;            0895   CHAR         1720   INVERT       2216   SOURCE
0080   (            0480   <            0897   CHAR+        1730 c J            2220   SPACE
0090   *            0490   <#           0898   CHARS        1750   KEY          2230   SPACES
0100   */           0530   =            0950   CONSTANT     1760 c LEAVE        2250   STATE
0110   */MOD        0540   >            0980   COUNT        1780 c LITERAL      2260   SWAP
0120   +            0550   >BODY        0990   CR           1800 c LOOP         2270 c THEN
0130   +!           0560   >IN          1000   CREATE       1805   LSHIFT       2310   TYPE
0140 c +LOOP        0570   >NUMBER      1170   DECIMAL      1810   M*           2320   U.
0150   ,            0580 c >R           1200   DEPTH        1870   MAX          2340   U<
0160   -            0630   ?DUP         1240 c DO           1880   MIN          2360   UM*
0180   .            0650   @            1250 c DOES>        1890   MOD          2370   UM/MOD
0190 c ."           0670   ABORT        1260   DROP         1900   MOVE         2380 c UNLOOP
0230   /            0680 c ABORT"       1290   DUP          1910   NEGATE       2390 c UNTIL
0240   /MOD         0690   ABS          1310 c ELSE         1980   OR           2410   VARIABLE
0250   0<           0695   ACCEPT       1320   EMIT         1990   OVER         2430 c WHILE
0270   0=           0705   ALIGN        1345   ENVIRONMENT? 2033 c POSTPONE     2450   WORD
0290   1+           0706   ALIGNED      1360   EVALUATE     2050   QUIT         2490   XOR
0300   1-           0710   ALLOT        1370   EXECUTE      2060 c R>           2500 c [
0310   2!           0720   AND          1380 c EXIT         2070 c R@           2510 c [']
0320   2*           0750   BASE         1540   FILL         2120 c RECURSE      2520 c [CHAR]
0330   2/           0760 c BEGIN        1550   FIND         2140 c REPEAT       2540   ]
0350   2@           0770   BL           1561   FM/MOD       2160   ROT
0370   2DROP        0850   C!           1650   HERE         2162   RSHIFT
```

A system may ship some of Core as source only, with a documented way to load it ([3]). No standard Word may change the system's state so that another behaves differently, except where the standard says so ([3]).

## 3. Semantics that are easy to get wrong

"Test" is where the files catch a mistake. "None" means they don't.

### Text interpreter and parsing

| Word or rule | Obligation | Test |
|---|---|---|
| Interpreter | Look the name up first, then try a number ([3.4]). Names may start with digits. | `1S`, `2ND`, `2K`: [core:41], [plus:295] |
| Parsing | A parsed name consumes exactly one trailing delimiter. With no delimiter left, `>IN` becomes the line's length ([3.4.1]). | The `-2` and `-6` jumps at [prelim:75-81] |
| `>IN` | A real Cell the program may write. The interpreter reads it again for every token ([6.1.0560], [3.3.3.5]). | [prelim:15-17], [core:800-807], [plus:186-187] |
| `SOURCE` | The current line, or the string being `EVALUATE`d ([6.1.2216], [3.3.3.5]). | [prelim:1-3], [core:795-797] |
| `EVALUATE` | The string itself becomes the input buffer, so `SOURCE` returns the same address and length: a copy fails. Save and restore the whole input source ([6.1.1360]). It doesn't save `STATE` ([A.6]). | No copy: [core:795-797]. `STATE`: none |
| `WORD` | Skip leading delimiters, parse to the delimiter or end of line, return a counted string. Empty rest of line gives length zero ([6.1.2450]). | Empty: [core:812-813]. Skipping: none |
| `(` `."` `S"` `ABORT"` | The text ends at the delimiter. No space is needed after it ([3.4.1]). | [plus:218-220] |
| Numbers | `#`, `$` and `%` prefixes with the minus after the prefix, and `'c'`, all leaving `BASE` alone, interpreting and compiling ([3.4.1.3]). | [plus:228-254], including `'''` |
| Number range | Literals up to 65535 convert into a Cell ([3.1.3.2]). | `8000` in hex: [core:70] |
| Letter digits | Output uses upper case ([3.2.1.2]). The same section says lower case "should" convert the same. | Upper: [core:879-882]. Lower required: [plus:247-248] |
| `>NUMBER` | No sign and no prefix. Stop at the first bad character and return where ([6.1.0570]). | [core:893-920], one-character strings only for the stop |
| Case | Upper-case spellings of standard Words must be found. Folding is optional ([3.4.2]). Data is not folded. | `'z'` and `'Z'`: [plus:234-235] |

### Compiler and Dictionary

| Word or rule | Obligation | Test |
|---|---|---|
| `:` and `;` | The new Word can't be found until `;` ([6.1.0450], [6.1.0460]). A later definition of a name wins ([3.3.1.1]). | [core:1003-1005] |
| `POSTPONE` | Appends compilation semantics, whether the Word is immediate or not ([6.1.2033]). It must also work on `;` and `\`, with the result later run from interpretation state. | [core:652-657], [core:751-754], [core:420] |
| `'` and `[']` | `'` parses when it runs, `[']` when compiling, and both give the same xt ([6.1.0070], [6.1.2510]). | [core:640-642] |
| `FIND` | Takes a counted string. Returns 1 for immediate, -1 otherwise, the same address and 0 when absent ([6.1.1550]). An unnamed Word must not match the empty string. | [core:645-646], [plus:272-282] |
| `IMMEDIATE` | Sets, doesn't toggle, and applies to the most recent definition, whatever made it, even when run from inside another Word later ([6.1.1710]). | [plus:192-212] |
| `STATE` | Non-zero while compiling. Only `:`, `;`, `[`, `]`, `ABORT`, `QUIT` and `:NONAME` change it ([6.1.2250]). | [core:659-662] |
| `LITERAL` | Takes a value that sits on top of any control-flow items on the data stack ([6.1.1780], [3.2.3.2]). | [core:630], [plus:195] |
| Immediate Words that leave values | Values left while compiling lie above the control-flow items and must not upset them. | [core:787-790], [plus:196-198] |
| `CREATE` | The data field is exactly what `HERE` returns right after it ([6.1.1000], [A.6]). | [prelim:142], [core:758-760] |
| `DOES>` | Changes the most recent definition and may do so again. Data field and `>BODY` stay valid ([6.1.1250], [6.1.0550]). | [core:756-772], [plus:294-295] |
| Control flow | Any match whose stack effects agree is legal: two `WHILE`s with `ELSE`, several `ELSE`s, `IF` closed by `REPEAT` ([3.4.1.2]). Checking that pairs match by type must allow these. | [core:687-688], [plus:179], [plus:287] |
| `RECURSE` | Any number per definition ([6.1.2120]). | [plus:164-173] |
| `S"` and `."` | The text is copied out of the input line when compiled. The returned string may be read-only ([6.1.2165], [3.3.3.4]). Interpretation semantics are undefined in Core. | [core:777-785] |
| `ALLOT` | Signed: negative releases, zero does nothing ([6.1.0710]). A full-Dictionary check must allow negatives. | [prelim:144], [plus:300-301] |
| `VARIABLE` | One aligned Cell. Initialising it is the program's job ([6.1.2410]). | [core:747-749] |

### Loops

| Word | Obligation | Test |
|---|---|---|
| `LOOP` | Ends when the index equals the limit after adding one, modulo 65536 ([6.1.1800]). Equal limit and start runs a full cycle. | Equal limit and start: none |
| `+LOOP` | Ends when the index crosses the boundary between limit minus one and limit, in either direction, not when it equals or passes the limit. An increment of zero never ends ([6.1.0140]). | [plus:50-67], [plus:83-124], [plus:144-159] |
| `LEAVE` | Leaves the innermost loop at once, from inside an `IF`. Several may share a loop ([6.1.1760]). | One per loop: [core:725-728]. Several: none |
| `UNLOOP` | One per nesting level before `EXIT` ([6.1.2380]). After one, `I` is the outer index. | [core:730-736] |
| `I` and `J` | Stay available across `+LOOP` and nesting ([6.1.1680], [6.1.1730]). | [core:715-723] |

### Arithmetic and memory

| Word | Obligation | Test |
|---|---|---|
| Flags | True is all bits set, false all clear ([3.1.3.1]). | [prelim:53-54] and throughout |
| `<` `>` `MIN` `MAX` | Signed, correct across overflow ([6.1.0480]). | [core:133-160] |
| `2/` | Arithmetic shift, so `-1 2/` is -1 and not division by two ([6.1.0330]). | [core:77] |
| `LSHIFT` `RSHIFT` | Logical, counts 0 to 15. More is ambiguous ([6.1.1805], [6.1.2162]). | [core:81-94] |
| Double Cells | High Cell on top of the stack ([3.1.4.1]). `2!` puts the top Cell at the lower address ([6.1.0310]). | [core:291], [core:574-576] |
| `UM*` `M*` | The full 32-bit product ([6.1.2360], [6.1.1810]). | [core:307-340] |
| `FM/MOD` `SM/REM` | Both required, floored and symmetric, whatever `/` does ([6.1.1561], [6.1.2214]). | [core:345-409] |
| `UM/MOD` | The whole unsigned range ([6.1.2370]), so shift-and-subtract needs a 17th bit in the partial remainder. | [core:417] |
| `/` `MOD` `/MOD` `*/` `*/MOD` | One of the two roundings. `*/` keeps a double intermediate ([6.1.0100], [3.2.2.1]). | [core:419-543] accepts either |
| `ABS` | Returns unsigned, so the most negative number gives `$8000` ([6.1.0690]). | [core:283] |
| `C@` | Zero-extends ([6.1.0870]). | None: no byte of `$80` or above is fetched |
| `MOVE` | Correct for overlap in both directions. The count is unsigned address units ([6.1.1900]). | [core:954-958], 3 bytes at most |
| `FILL` | A count of zero writes nothing ([6.1.1540]). | [core:933] |
| `#>` | Drops a double and returns the string built backwards by `#` and `HOLD` ([6.1.0040]). | [core:835-883] |

### System and Console

| Word | Obligation | Test |
|---|---|---|
| `QUIT` | Empties the return stack, **not the data stack**, sets interpretation state and makes the Console the input, shows no message. The prompt appears only when interpreting and without error ([6.1.2050]). | Never called. Values must survive from line to line: [core:548-551] |
| `ABORT` | Empties the data stack, then does what `QUIT` does, with no message ([6.1.0670]). | None |
| `ABORT"` | Always consumes the flag. If non-zero it shows the text and does at least `ABORT` ([6.1.0680]). | None |
| `KEY` | Waits, doesn't echo, and can receive every character from 32 to 126 ([6.1.1750]). | None |
| `ACCEPT` | Shows characters as received, stores at most n, appends nothing, returns the count ([6.1.0695]). It must leave the line being interpreted intact. | Only that the stack balances: [core:998] |
| `ENVIRONMENT?` | Names are separate from the Dictionary. An unknown query gives a single false. A known one gives value and true, and must stay known ([6.1.1345], [3.2.6]). | None |
| `.` and `U.` | Number plus one trailing space ([3.2.1.3]). | Output only |
| `SPACES` | Zero or negative shows nothing ([6.1.2230]). | Zero, output only |

`ENVIRONMENT?` may answer false to every query: the glossary only says what to return when the system "treats the attribute as unknown". The two example tests on its web page can't serve as our test: they use `S"` while interpreting, and `DUP 0= XOR INVERT` gives false for either flag, so the first always fails (by reading).

## 4. Minimums, options and documentation

### Minimums

| Item | Standard | What the four files need |
|---|---|---|
| Character | 8 bits or more, unsigned, graphic 32 to 126 ([3.1.2]) | The same |
| Cell | 16 bits or more, a multiple of a character ([3.1.3]) | 16 bits works. Two's complement and wrap are assumed ([core:9-11]) |
| Signed, unsigned | -32767 to 32767, 0 to 65535 ([3.1.3.2]) | -32768 as well |
| Double | -2147483647 to 2147483647, unsigned to 4294967295 ([3.1.4.1]) | Full unsigned range ([core:916-920]) |
| Counted string | 255 ([3.1.3.4]) | 64 in `."` ([core:978]) |
| Parsed string | 0 up to the counted-string maximum ([3.4.1]) | 76 in `(` ([prelim:51]) |
| Definition name | 1 to 31 characters, longer allowed ([3.3.1.2]) | All 31 significant: two differ only in the last ([plus:263-267]) |
| `ENVIRONMENT?` string | 31 ([6.1.1345]) | Unused |
| Terminal input buffer | 80 characters ([3.3.3.5]) | Exactly 80, on 20 comment lines of `coreplustest.fth` |
| `WORD` region | 33 characters ([3.3.3.6]) | **49 plus the count** ([prelim:213]). Six passing messages exceed 33 ([prelim:53-55], [prelim:84], [prelim:213], [prelim:231]), and two of exactly 33 don't fit 33 with a count ([prelim:49], [prelim:233]) |
| Pictured numeric buffer | 2n+2, so 34 for 16-bit Cells ([3.3.3.6]) | 32 digits ([core:861-870]) |
| `PAD` | 84, if provided. It is Core Extension ([3.3.3.6]) | Unused |
| Data stack | No minimum. Document it ([4.1.3]) | At least 12 Cells, see below |
| Return stack | No minimum. Document it ([4.1.3]) | At least 12 nested calls, plus whatever the interpreter holds |
| Dictionary | No minimum. Document it ([4.1.3]) | 213 definitions, about 1,082 name characters and 1,079 compiled tokens, plus data (scan, counts both sides of conditional lines) |

- The stack figures come from a Python model of the Ackermann definition at [plus:164-169]: `2 4 ACK` makes 65 calls, 12 deep, with at most 12 data Cells. It is the deepest point found by reading, not a measured high-water mark for the OS.
- `tester.fr` saves results in 32 Cells (`20` is read in hex, [tester:15], [tester:37]) without a bounds check. The largest result is 7.
- `ABUF` is 80 bytes for the same reason ([core:989-993]). The largest base is 36, written `24` ([core:847]).
- If `(`, `."` and `S"` share the `WORD` buffer, that buffer needs 76.
- `WORD`'s region and the `#>` buffer may overlap and may move whenever data space is allocated or a definition made ([3.3.3.6]). The files allow that. The text a program gets from `WORD` must survive until it has used it: [core:891], [prelim:49].

### The 35 options of 4.1.1

Each must be documented ([4.1.1]). The last column is only what the standard or the tests rule out.

| # | Option | Room to choose | Limits from the tests |
|--:|---|---|---|
| 1 | Aligned addresses | Any subset of addresses ([3.1.3.3]) | 1 or 2 bytes both pass ([core:556-559]) |
| 2 | `EMIT` of non-graphic characters | Any ([6.1.1320]) | None |
| 3 | `ACCEPT` editing | Any or none ([6.1.0695]) | None |
| 4 | Character set | At least 32 to 126 ([3.1.2]) | ASCII values |
| 5 | Character-aligned addresses | Any subset ([3.1.3.3]) | None beyond consistency |
| 6 | Case matching in extended character sets | Any ([3.4.2]) | None |
| 7 | Which controls count as a space | None, some or all ([3.4.1.1]) | None: the files have no controls |
| 8 | Control-flow stack | Data stack, separate stack, other ([3.2.3.2]) | Must balance around `T{ : name ... ; -> }T` |
| 9 | Digits above 35 | Any ([3.2.1.2]) | None |
| 10 | Display when `ACCEPT` ends | Any ([6.1.0695]) | None |
| 11 | `ABORT"` sequence | Anything that includes `ABORT` ([6.1.0680]) | None |
| 12 | Line terminator | Any ([3.2.4.1]) | LF in the files |
| 13 | Counted-string maximum | 255 or more ([3.1.3.4]) | None further |
| 14 | Parsed-string maximum | Up to option 13 ([3.4.1]) | 76 |
| 15 | Name maximum | 31 or more ([3.3.1.2]) | 31, all significant |
| 16 | `ENVIRONMENT?` string maximum | 31 or more ([6.1.1345]) | None |
| 17 | Choosing the input device | Any ([3.2.4.1]) | `ACCEPT` reads it, not the source being loaded |
| 18 | Choosing the output device | Any ([3.2.4.2]) | None |
| 19 | How the Dictionary is compiled | Any ([3.3]) | None |
| 20 | Bits per address unit | Implementation defined ([3.1.3.3]) | 8 on this Machine |
| 21 | Number representation | Three allowed ([3.2.1.1]) | Two's complement |
| 22 | Numeric ranges | At least the minimums above | Full two's complement |
| 23 | Read-only data space | String literals, some system regions ([3.3.3.4]) | `>IN` and `BASE` are written |
| 24 | `WORD` buffer | 33 or more ([3.3.3.6]) | 49 or more |
| 25 | Cell in address units | 16 bits or more ([3.1.3]) | None further |
| 26 | Character in address units | 1 or more ([3.1.2]) | No wider than a Cell ([core:606-607]) |
| 27 | Terminal input buffer | 80 or more ([3.3.3.5]) | 80 |
| 28 | Pictured numeric buffer | 34 or more ([3.3.3.6]) | 32 |
| 29 | `PAD` | 84 or more, if provided ([3.3.3.6]) | None |
| 30 | Case sensitivity | Either ([3.4.2]) | Either, see section 5 |
| 31 | Prompt | Should contain "ok" in any case ([3.4]) | None |
| 32 | Division rounding | Floored or symmetric ([3.2.2.1]) | Either, consistent across five Words |
| 33 | True value of `STATE` | Any non-zero ([6.1.2250]) | None |
| 34 | Values after overflow | Any. It is never reported ([3.2.2.2]) | Wrap modulo 65536 |
| 35 | Whether a Word is findable after `DOES>` compiles | Either ([6.1.1250]) | None |

Outside that list the standard also leaves open the way to load source-only Core ([3]) and the graphic forms outside 32 to 126 ([3.1.2.1]).

### Ambiguous conditions

For each, a system documents one or more of: ignore and continue, show a message, run a given Word, drop to interpretation, or another action ([3.4.4]). Different actions in different circumstances are allowed. [4.1.2] lists 38. Nine concern only Extension Words the map doesn't include. The other 29:

- Interpreter: unknown name that is no number, name too long, end of input where a name is needed, Word without interpretation semantics interpreted or ticked, `>IN` past the buffer, parsed string too long, `WORD` result too long, name not found by `'`, `[']` or `POSTPONE`.
- Resources: either stack overflowing, either stack underflowing, no room for loop parameters, Dictionary full, pictured buffer overflow.
- Memory: address outside data space, misaligned access, misaligned `,` or `C,`, writing the input buffer or a string literal, releasing space that holds definitions.
- Values: wrong argument type, division by zero, result out of range, shift of 16 or more, `DO` arguments of different types.
- Structure: loop Word without loop parameters, `RECURSE` after `DOES>`, `IMMEDIATE` with no named latest definition, `>BODY` or `DOES>` on a Word not from `CREATE`, `#`, `#S`, `HOLD` or `SIGN` outside `<#` and `#>`.
- The glossary adds one that 4.1.2 omits: `ACCEPT` with a count of zero or above 32,767 ([6.1.0695]).

### Other documents and the label

- [4.1.3]: non-standard Words that use `PAD`, terminal facilities, data space available to programs, return stack in Cells, data stack in Cells, Dictionary space the system takes.
- A system meeting all of it is labelled "Forth-2012 System". Missing any minimum adds "with Environmental Restrictions". The chosen Core Extension Words add "Providing name(s) from the Core Extensions word set" ([5.1.2]). `BYE` adds the same phrase for Programming-Tools Extensions ([15.5.1]).
- A partial Block or Programming-Tools word set (`.S`, `WORDS`) has no label phrase ([7.5.1], [15.5.1]). If `BLK` and Block loading exist, the standard extends `EVALUATE` to zero `BLK`, changes `\` to skip one line of the Block, and adds its own documentation list ([7.6.1.1360], [7.6.2.2535], [7.4.1]).

## 5. What the files need

### Words outside Core

Scan of executed tokens, comments and strings removed.

| File | Uses | Must come from the OS when loaded in order |
|---|---|---|
| `prelimtest.fth` | Probes `\`, `TRUE`, `FALSE` and `HEX`, defines the missing ones, tests all four ([prelim:188-203]) | Nothing |
| `tester.fr` | `\`, `HEX`, `FALSE` | Nothing |
| `core.fr` | `\`, `HEX`, `.(` | `.(` |
| `coreplustest.fth` | `\`, `.(`, `NIP`, `TUCK`, `:NONAME` | `.(`, `NIP`, `TUCK`, `:NONAME` |

The fallback `\` discards the rest of the input buffer ([prelim:66], [prelim:188]), which is right for lines and wrong for Blocks.

The suite's own runner and report are not usable as they stand: `runtests.fth` needs `INCLUDED` ([runner:9-14]), `utilities.fth` needs `2>R` and `2R>` ([utilities:98-99]), and `errorreport.fth` needs `.R` ([report:59]) despite saying it uses only Core.

### Order and state between files

| File | Needs before it | Leaves behind |
|---|---|---|
| `prelimtest.fth` | A working interpreter with any `BASE` ([prelim:22-29]) | Decimal, empty stack, `#ERRS` |
| `tester.fr` | `\`, `HEX`, `FALSE` | **Hex**, `#ERRORS` at 0, `T{`, `->`, `}T`, `TESTING` |
| `core.fr` | The tester | Hex, its constants |
| `coreplustest.fth` | The tester, and `<TRUE>`, `MAX-INT`, `MIN-INT`, `MAX-UINT` from `core.fr` ([plus:26-29]) | Decimal. `#ERRORS` is the total for both files |

### Input

- The four files are 233, 66, 1,009 and 305 lines. Each ends in LF, so plain concatenation is safe.
- `ACCEPT` consumes one line from the user input device ([core:993], [core:998]). Piped, that is the empty [core:999], and it prints `RECEIVED: ""`. A harness that removes blank lines feeds it the next comment. A loader that reads files some other way leaves it waiting on the Console.
- Nothing else reads input. `KEY` is never called.
- Inserting or removing characters between a jump and its target breaks it. The jumps at [plus:186-187] are absolute columns, so even an indent breaks them. The prelim test needs exactly one space between `?T~` and `Error` ([prelim:73]). Trimming line ends and collapsing runs of spaces happen to move no executed jump target in these files (scan), but do change printed text.
- The 20 lines of 80 characters are all comments. A buffer of 79 that treats the rest as a new line would execute the last character left over, mostly a `-`.
- If a CR before LF were kept as data, the empty-line `WORD` test fails ([core:812-813]).

### Words inside strings given to `EVALUATE`

| String | Where | Needs |
|---|---|---|
| `123` | [core:777], run at [core:782] and [core:787] | Hex number, interpreting and compiling |
| `123 1+` | [core:778] | Compiling a Word from inside a string |
| `: GE4 345 ;` | [core:779] | A definition made from a string that ends without a space |
| `SOURCE` | [core:795] | The caller's own address and length |
| `123 RESCAN?` | [core:806] | `0 >IN !` rescans the string, 5 times |

All five are in `core.fr`. Only Core Words and Words the file defines appear in them.

### `>IN` tricks and other surprises

- `n >IN +!` is the prelim test's `IF`: it skips filler such as `xxSOURCE`, landing inside a token ([prelim:16]). A negative n moves back, so `?~` lands on its own `~` and `Error` re-reads itself as a message ([prelim:75-81]).
- `0 >IN !` makes loops of single lines ([prelim:105-106], [prelim:226], [core:800-804]).
- [plus:186] computes its landing column from `DEPTH`. One leftover Cell changes where it lands. [plus:187] jumps over `GCD CALCULATION`, two undefined Words that must never be looked up.
- `~`, `TESTING` and `GS4` discard the rest of the line with `SOURCE >IN !` ([prelim:66], [tester:61-65], [core:815-817]).
- Tests span lines, with values on the stack and definitions left open across them ([core:687-688], [core:802-804], [plus:164-169]).
- `IFFLOORED` and `IFSYM` run a postponed `\` to comment out five definitions each ([core:419-437]). If `-3 2 /` is neither -2 nor -1, both groups are skipped and the tests die on unknown Words, uncounted.
- `GD9` compiles `->` and `}T` into itself, so four tests have no `}T` on their line ([plus:98-105]).
- `HERE` is compared across Words and lines ([core:548-569], [core:759-771]). The interpreter must not move it while parsing.

### Case

- All standard Words are spelled in upper case, so case-sensitive and case-insensitive systems can both run the files (scan).
- Case-insensitive, `ERROR` redefines the prelim test's `Error` ([prelim:81], [tester:28]). Nothing uses the old one afterwards. No other two test names differ only in case.
- No test requires `abc` and `ABC` to be different Words. The tests do require lower-case hex digits ([plus:230], [plus:247-248]) and unfolded character literals ([plus:234-235]).

## 6. How failure shows, and how a harness can tell

### What the files report

| File | On failure | At the end |
|---|---|---|
| `prelimtest.fth`, Pass 1 to 23 | An unknown `x...` token, a wrong message or a missing one. Never counted | |
| `prelimtest.fth`, Errors 1 to 57 | `Error` text and 1 added to `#ERRS` ([prelim:80-82]) | `0 tests failed out of 57 additional tests`, then an end marker ([prelim:223-233]). The 57 is literal text |
| `tester.fr` with `core.fr` and `coreplustest.fth` | `INCORRECT RESULT: ` or `WRONG NUMBER OF RESULTS: ` plus the current line, stack emptied, 1 added to `#ERRORS` ([tester:26-59]) | Two `.(` markers ([core:1007], [plus:305]). No count and no verdict |

`tester.fr` prints one `*` for each `TESTING` line: 23 from `core.fr` and 15 from `coreplustest.fth`. Setting `VERBOSE` prints the lines instead, which locates a hang ([tester:17-21]).

### How a broken run looks like a pass

- An unknown Word or abort inside `T{ ... }T` means `}T` never runs, so nothing is counted ([tester:48-59]). The same inside a prelim line skips its `?~`.
- A definition left open by an error compiles the following lines, trailer included.
- `#ERRS` starts only at [prelim:80] and misses everything before it.
- Data stack underflow inside a test gives `->` a negative depth. Its `DO` then stores tens of thousands of Cells over memory ([tester:42-46], by reading). `EMPTY-STACK` expects `DEPTH` to be able to go negative ([tester:23-24]).
- A broken `=` or `}T` can make every test pass. The prelim test checks `=` ([prelim:91-95]). Nothing checks that `}T` can fail.
- If the OS echoes input, the transcript holds every marker and every failure string from the source, passed or not. The first ten Pass lines are copies of their source lines by design, so an error handler that shows the bad line also prints "Pass".
- The expected outputs in `doc/` are out of date: 56 tests where the file says 57 ([prelimout]), 32-bit number ranges and fewer stars than today's files ([testout]). They are not golden files.
- An exit code is one byte. Passing the count straight through turns 256 errors into 0.

### What the Simulator gives a harness

Read in this repo at `e4e9ddd`, not run:

- A write to the Exit device becomes the exit code, masked to a byte ([ExitDevice][exitdev]).
- 2 is a usage error and 125 a Simulator failure, which includes a piped run stopping any other way ([Main][main], [RunCommand][runcmd]). A program can write those codes too.
- End of piped input sets bit 5 of the Console's status and stops nothing. A data read then returns 0 ([Console][console]).
- `run` has no Step limit, so a hung OS runs until the host kills it ([Machine][machine]).

### Options for telling a complete run from a dead one

They can be combined. The OS's decisions are not made here.

| Option | Catches | Misses or costs |
|---|---|---|
| Trailer after the last file exits 0 only if `#ERRS` and `#ERRORS` are both 0, else a clamped non-zero code | Counted failures, and any death before the trailer if every other exit is non-zero | Uncounted interpreter errors. `BYE` takes no argument ([15.6.2.0830]), so the code needs another route to the Exit device |
| Make any interpreter error fatal, or count it | Unknown Words, aborts, underflow | Differs from "back to `ok`" at the prompt, so it needs a mode, a variable or output checks |
| End of input before the trailer exits non-zero | Truncated input, swallowed trailer | Needs the OS to watch the EOF bit |
| Host timeout | Hangs | A limit to pick |
| Count completed tests by wrapping `}T` after loading the tester. A full run is 739 (638 and 101, from the scan) | Skipped lines and aborts inside tests | A wrapper to write. Covers neither the prelim test nor lines outside `T{` |
| Compare the transcript, with a marker the source doesn't contain literally | Output Words, order, missing sections | Echo and prompts must be predictable |
| A deliberate failure that must be counted | A tester that can't fail | One more expected count. The prelim test has two commented out ([prelim:205-207]) |

## 7. What the tests don't test

Never used (scan): `KEY`, `QUIT`, `ABORT`, `ABORT"`, `ENVIRONMENT?`.

Run but can't fail on what they show:

- `.`, `U.`, `."`, `CR`, `EMIT`, `SPACE`, `SPACES` and `TYPE`: `OUTPUT-TEST` checks only that the stack balances ([core:963-983]). The same goes for [plus:220] and for the prelim messages.
- `ACCEPT`: any length and any text pass ([core:991-998]).
- The wrap tests in `GD9` compare a result with itself when their wrap check is false ([plus:98-105]).
- The `/` family is compared with the system's own `FM/MOD` or `SM/REM` ([core:428-543]). Those two are tested against constants.
- Alignment is compared with the system's own `ALIGNED` ([core:556-559]).

Obligations with no test (by reading all four files):

- Interpreter: recovery from an unknown Word, underflow, overflow or a full Dictionary. The prompt. A line longer than the buffer.
- Parsing: `WORD` skipping several leading delimiters. Names longer than 31.
- `EVALUATE`: nesting inside another, an empty string, an error inside one, `STATE` carried out of one.
- `FIND` while compiling. `'`, `[']` and `EXECUTE` on built-in Words. `POSTPONE` of `IF` and other control Words.
- `>NUMBER`: lower-case digits, the characters between `9` and `A`, stopping mid-string.
- `LOOP` with limit equal to start. Several `LEAVE`s in a loop.
- Division by zero and quotient overflow.
- `C@` of bytes from `$80` up. `MOVE` and `FILL` of more than 3 bytes, so a page crossing may never be exercised. `SPACES` with a negative count.

## 8. Licences

| File | Terms in the file | Consequence |
|---|---|---|
| `tester.fr` | Johns Hopkins University, 1995: "MAY BE DISTRIBUTED FREELY AS LONG AS THIS COPYRIGHT NOTICE REMAINS" ([tester:5-6]) | Copying is allowed. Modification is not mentioned, though the suite itself carries changes |
| `core.fr` | The same ([core:5-6]) | The same |
| `coreplustest.fth` | Public domain, may be distributed and modified, "please retain this notice" ([plus:3-9]) | Free |
| `prelimtest.fth` | None | No grant found |

The repository has no licence file and GitHub reports no licence ([repo]). The suite's `prelimtest.md` has no terms either. Options: fetch by commit and hash at test time, vendor the three files with notices and fetch or ask about the prelim test, or ask upstream first. This is not legal advice.

## Open points

- `TRUE` can stay among the map's Words as a choice, but the test files don't need it.
- The map's own tests for skipped Words name four. `ENVIRONMENT?` is the fifth.
- "Zero errors through the exit code" needs decisions on interpreter errors, early end of input and how a count reaches the Exit device.
- Whether to vendor `prelimtest.fth`, which has no licence.
- Not checked: running any file on any Forth, real high-water marks for stacks and Dictionary, how JLine passes piped bytes to the Console, the full glossary text of forth-standard.org against the PDF (the PDF was used), errata and proposals after 2014, the suite's other files beyond the runner, utilities and report.

[15.5.1]: https://forth-standard.org/standard/tools#subsection.15.5.1
[15.6.2.0830]: https://forth-standard.org/standard/tools/BYE
[3]: https://forth-standard.org/standard/usage#chapter.3
[3.1.2]: https://forth-standard.org/standard/usage#subsection.3.1.2
[3.1.2.1]: https://forth-standard.org/standard/usage#subsubsection.3.1.2.1
[3.1.3]: https://forth-standard.org/standard/usage#subsection.3.1.3
[3.1.3.1]: https://forth-standard.org/standard/usage#subsubsection.3.1.3.1
[3.1.3.2]: https://forth-standard.org/standard/usage#subsubsection.3.1.3.2
[3.1.3.3]: https://forth-standard.org/standard/usage#subsubsection.3.1.3.3
[3.1.3.4]: https://forth-standard.org/standard/usage#subsubsection.3.1.3.4
[3.1.4.1]: https://forth-standard.org/standard/usage#subsubsection.3.1.4.1
[3.2.1.1]: https://forth-standard.org/standard/usage#subsubsection.3.2.1.1
[3.2.1.2]: https://forth-standard.org/standard/usage#subsubsection.3.2.1.2
[3.2.1.3]: https://forth-standard.org/standard/usage#subsubsection.3.2.1.3
[3.2.2.1]: https://forth-standard.org/standard/usage#subsubsection.3.2.2.1
[3.2.2.2]: https://forth-standard.org/standard/usage#subsubsection.3.2.2.2
[3.2.3]: https://forth-standard.org/standard/usage#subsection.3.2.3
[3.2.3.2]: https://forth-standard.org/standard/usage#subsubsection.3.2.3.2
[3.2.4.1]: https://forth-standard.org/standard/usage#subsubsection.3.2.4.1
[3.2.4.2]: https://forth-standard.org/standard/usage#subsubsection.3.2.4.2
[3.2.6]: https://forth-standard.org/standard/usage#subsection.3.2.6
[3.3]: https://forth-standard.org/standard/usage#section.3.3
[3.3.1.1]: https://forth-standard.org/standard/usage#subsubsection.3.3.1.1
[3.3.1.2]: https://forth-standard.org/standard/usage#subsubsection.3.3.1.2
[3.3.3.4]: https://forth-standard.org/standard/usage#subsubsection.3.3.3.4
[3.3.3.5]: https://forth-standard.org/standard/usage#subsubsection.3.3.3.5
[3.3.3.6]: https://forth-standard.org/standard/usage#subsubsection.3.3.3.6
[3.4]: https://forth-standard.org/standard/usage#section.3.4
[3.4.1]: https://forth-standard.org/standard/usage#subsection.3.4.1
[3.4.1.1]: https://forth-standard.org/standard/usage#subsubsection.3.4.1.1
[3.4.1.2]: https://forth-standard.org/standard/usage#subsubsection.3.4.1.2
[3.4.1.3]: https://forth-standard.org/standard/usage#subsubsection.3.4.1.3
[3.4.2]: https://forth-standard.org/standard/usage#subsection.3.4.2
[3.4.3.2]: https://forth-standard.org/standard/usage#subsubsection.3.4.3.2
[3.4.4]: https://forth-standard.org/standard/usage#subsection.3.4.4
[4.1.1]: https://forth-standard.org/standard/doc#subsection.4.1.1
[4.1.2]: https://forth-standard.org/standard/doc#subsection.4.1.2
[4.1.3]: https://forth-standard.org/standard/doc#subsection.4.1.3
[5.1.1]: https://forth-standard.org/standard/label#subsection.5.1.1
[5.1.2]: https://forth-standard.org/standard/label#subsection.5.1.2
[6.1.0040]: https://forth-standard.org/standard/core/num-end
[6.1.0070]: https://forth-standard.org/standard/core/Tick
[6.1.0100]: https://forth-standard.org/standard/core/TimesDiv
[6.1.0140]: https://forth-standard.org/standard/core/PlusLOOP
[6.1.0310]: https://forth-standard.org/standard/core/TwoStore
[6.1.0330]: https://forth-standard.org/standard/core/TwoDiv
[6.1.0450]: https://forth-standard.org/standard/core/Colon
[6.1.0460]: https://forth-standard.org/standard/core/Semi
[6.1.0480]: https://forth-standard.org/standard/core/less
[6.1.0550]: https://forth-standard.org/standard/core/toBODY
[6.1.0560]: https://forth-standard.org/standard/core/toIN
[6.1.0570]: https://forth-standard.org/standard/core/toNUMBER
[6.1.0670]: https://forth-standard.org/standard/core/ABORT
[6.1.0680]: https://forth-standard.org/standard/core/ABORTq
[6.1.0690]: https://forth-standard.org/standard/core/ABS
[6.1.0695]: https://forth-standard.org/standard/core/ACCEPT
[6.1.0710]: https://forth-standard.org/standard/core/ALLOT
[6.1.0870]: https://forth-standard.org/standard/core/CFetch
[6.1.1000]: https://forth-standard.org/standard/core/CREATE
[6.1.1250]: https://forth-standard.org/standard/core/DOES
[6.1.1320]: https://forth-standard.org/standard/core/EMIT
[6.1.1345]: https://forth-standard.org/standard/core/ENVIRONMENTq
[6.1.1360]: https://forth-standard.org/standard/core/EVALUATE
[6.1.1540]: https://forth-standard.org/standard/core/FILL
[6.1.1550]: https://forth-standard.org/standard/core/FIND
[6.1.1561]: https://forth-standard.org/standard/core/FMDivMOD
[6.1.1680]: https://forth-standard.org/standard/core/I
[6.1.1710]: https://forth-standard.org/standard/core/IMMEDIATE
[6.1.1730]: https://forth-standard.org/standard/core/J
[6.1.1750]: https://forth-standard.org/standard/core/KEY
[6.1.1760]: https://forth-standard.org/standard/core/LEAVE
[6.1.1780]: https://forth-standard.org/standard/core/LITERAL
[6.1.1800]: https://forth-standard.org/standard/core/LOOP
[6.1.1805]: https://forth-standard.org/standard/core/LSHIFT
[6.1.1810]: https://forth-standard.org/standard/core/MTimes
[6.1.1900]: https://forth-standard.org/standard/core/MOVE
[6.1.2033]: https://forth-standard.org/standard/core/POSTPONE
[6.1.2050]: https://forth-standard.org/standard/core/QUIT
[6.1.2120]: https://forth-standard.org/standard/core/RECURSE
[6.1.2162]: https://forth-standard.org/standard/core/RSHIFT
[6.1.2165]: https://forth-standard.org/standard/core/Sq
[6.1.2214]: https://forth-standard.org/standard/core/SMDivREM
[6.1.2216]: https://forth-standard.org/standard/core/SOURCE
[6.1.2230]: https://forth-standard.org/standard/core/SPACES
[6.1.2250]: https://forth-standard.org/standard/core/STATE
[6.1.2360]: https://forth-standard.org/standard/core/UMTimes
[6.1.2370]: https://forth-standard.org/standard/core/UMDivMOD
[6.1.2380]: https://forth-standard.org/standard/core/UNLOOP
[6.1.2410]: https://forth-standard.org/standard/core/VARIABLE
[6.1.2450]: https://forth-standard.org/standard/core/WORD
[6.1.2510]: https://forth-standard.org/standard/core/BracketTick
[7.3.3]: https://forth-standard.org/standard/block#subsection.7.3.3
[7.4.1]: https://forth-standard.org/standard/block#subsection.7.4.1
[7.5.1]: https://forth-standard.org/standard/block#subsection.7.5.1
[7.6.1.1360]: https://forth-standard.org/standard/block/EVALUATE
[7.6.2.2535]: https://forth-standard.org/standard/block/bs
[A.6]: https://forth-standard.org/standard/rationale#section.A.6
[committee]: http://www.forth200x.org/
[console]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/machine/Console.java
[core]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr
[core:1003-1005]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L1003-L1005
[core:1007]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L1007
[core:12-13]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L12-L13
[core:133-160]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L133-L160
[core:17]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L17
[core:283]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L283
[core:291]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L291
[core:307-340]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L307-L340
[core:345-409]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L345-L409
[core:41]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L41
[core:417]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L417
[core:419-437]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L419-L437
[core:419-543]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L419-L543
[core:420]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L420
[core:428-543]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L428-L543
[core:5-6]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L5-L6
[core:548-551]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L548-L551
[core:548-569]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L548-L569
[core:556-559]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L556-L559
[core:574-576]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L574-L576
[core:606-607]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L606-L607
[core:630]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L630
[core:640-642]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L640-L642
[core:645-646]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L645-L646
[core:652-657]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L652-L657
[core:659-662]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L659-L662
[core:687-688]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L687-L688
[core:70]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L70
[core:715-723]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L715-L723
[core:725-728]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L725-L728
[core:730-736]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L730-L736
[core:747-749]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L747-L749
[core:751-754]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L751-L754
[core:756-772]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L756-L772
[core:758-760]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L758-L760
[core:759-771]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L759-L771
[core:77]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L77
[core:777]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L777
[core:777-785]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L777-L785
[core:778]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L778
[core:779]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L779
[core:782]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L782
[core:787]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L787
[core:787-790]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L787-L790
[core:795]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L795
[core:795-797]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L795-L797
[core:800-804]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L800-L804
[core:800-807]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L800-L807
[core:802-804]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L802-L804
[core:806]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L806
[core:81-94]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L81-L94
[core:812-813]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L812-L813
[core:815-817]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L815-L817
[core:835-883]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L835-L883
[core:847]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L847
[core:861-870]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L861-L870
[core:879-882]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L879-L882
[core:891]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L891
[core:893-920]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L893-L920
[core:9-11]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L9-L11
[core:916-920]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L916-L920
[core:933]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L933
[core:954-958]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L954-L958
[core:963-983]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L963-L983
[core:978]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L978
[core:989-993]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L989-L993
[core:991-998]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L991-L998
[core:993]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L993
[core:998]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L998
[core:999]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/core.fr#L999
[exitdev]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/machine/ExitDevice.java
[machine]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/machine/Machine.java#L322-L340
[main]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/cli/Main.java
[pdf]: http://www.forth200x.org/documents/forth-2012.pdf
[pin]: https://github.com/Forth-Standard/forth-standard-test-suite/commit/4c87e5659c5b79bd7a6664daa56603df080c6f9b
[plus]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth
[plus:144-159]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L144-L159
[plus:164-169]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L164-L169
[plus:164-173]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L164-L173
[plus:179]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L179
[plus:186]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L186
[plus:186-187]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L186-L187
[plus:187]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L187
[plus:192-212]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L192-L212
[plus:195]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L195
[plus:196-198]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L196-L198
[plus:218-220]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L218-L220
[plus:220]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L220
[plus:228-254]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L228-L254
[plus:230]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L230
[plus:234-235]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L234-L235
[plus:247-248]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L247-L248
[plus:26-29]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L26-L29
[plus:263-267]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L263-L267
[plus:272-282]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L272-L282
[plus:274]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L274
[plus:287]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L287
[plus:294-295]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L294-L295
[plus:295]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L295
[plus:3-9]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L3-L9
[plus:300-301]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L300-L301
[plus:305]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L305
[plus:50-67]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L50-L67
[plus:83-124]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L83-L124
[plus:98-105]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/coreplustest.fth#L98-L105
[prelim]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth
[prelim:1-3]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L1-L3
[prelim:105-106]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L105-L106
[prelim:142]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L142
[prelim:144]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L144
[prelim:15-17]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L15-L17
[prelim:16]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L16
[prelim:188]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L188
[prelim:188-203]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L188-L203
[prelim:196-198]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L196-L198
[prelim:205-207]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L205-L207
[prelim:213]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L213
[prelim:22-29]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L22-L29
[prelim:223-233]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L223-L233
[prelim:226]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L226
[prelim:231]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L231
[prelim:233]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L233
[prelim:49]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L49
[prelim:51]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L51
[prelim:53-54]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L53-L54
[prelim:53-55]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L53-L55
[prelim:66]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L66
[prelim:73]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L73
[prelim:75-81]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L75-L81
[prelim:80]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L80
[prelim:80-82]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L80-L82
[prelim:81]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L81
[prelim:84]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L84
[prelim:91-95]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/prelimtest.fth#L91-L95
[prelimout]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/doc/prelimtestoutput.txt
[readme]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/README.md
[repo]: https://github.com/Forth-Standard/forth-standard-test-suite
[report:59]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/errorreport.fth#L59
[runcmd]: https://github.com/vladdu/6502/blob/e4e9dddd43722b2b604b397d1bb184b8a6a95e4e/src/main/java/cli/RunCommand.java
[runner:9-14]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/runtests.fth#L9-L14
[site]: https://forth-standard.org/
[tester]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr
[tester:15]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L15
[tester:17-21]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L17-L21
[tester:20]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L20
[tester:23-24]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L23-L24
[tester:26-34]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L26-L34
[tester:26-59]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L26-L59
[tester:28]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L28
[tester:37]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L37
[tester:42-46]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L42-L46
[tester:48-59]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L48-L59
[tester:5-6]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L5-L6
[tester:61-65]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/tester.fr#L61-L65
[testout]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/doc/testoutput.txt
[utilities:98-99]: https://github.com/Forth-Standard/forth-standard-test-suite/blob/4c87e5659c5b79bd7a6664daa56603df080c6f9b/src/utilities.fth#L98-L99
[wordlist]: https://forth-standard.org/standard/core
