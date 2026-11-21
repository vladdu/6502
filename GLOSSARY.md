# 6502

A from-scratch learning project: a 6502 toolchain and a fictional computer to run it on, later hosting a small hand-written operating system.

## Language

**CPU**:
The NMOS 6502 with its documented instruction set, including decimal mode and the original hardware quirks (such as the JMP indirect page-wrap bug).
_Avoid_: processor, core, 65C02

**Machine**:
The project's own fictional computer: a CPU plus a memory map and a set of devices. It imitates no real computer.
_Avoid_: system, board, platform, emulated computer

**Device**:
A piece of Machine hardware the CPU reaches through the memory map, such as a console or timer.
_Avoid_: peripheral, chip

**I/O region**:
The part of the Machine's address space where Devices live, including space reserved for Devices not yet built.
_Avoid_: I/O page, hardware area

**Console**:
The Machine's serial-style character Device: bytes in from the keyboard, bytes out to the terminal. It has no screen memory.
_Avoid_: terminal, UART, ACIA, screen

**Timer**:
The Machine's Device that counts CPU cycles and can raise an interrupt.
_Avoid_: clock, RTC

**Disk**:
The Machine's block storage Device, backed by an image file on the host.
_Avoid_: drive, block device, storage

**Sector**:
The unit the Disk reads and writes: 256 bytes, addressed by a sector number counted from 0.
_Avoid_: block, record

**Exit device**:
A Device whose only job is to stop the Simulator, with the written byte as the exit code.
_Avoid_: halt port, debug port

**Simulator**:
The program that runs a Machine.
_Avoid_: emulator, VM

**Monitor**:
The Simulator's interactive debugger: load, step, breakpoints, registers, memory, disassembly.
_Avoid_: debugger, shell, console

**Step**:
The smallest unit the Machine advances by: one CPU instruction, or one interrupt entry (pushing state and jumping to the vector, before any handler instruction runs).
_Avoid_: tick, cycle

**Program mode**:
Starting the Machine by loading a binary at a given address and setting PC there, bypassing the reset vector.
_Avoid_: direct run, raw load

**ROM boot**:
Starting the Machine the way hardware does: an image at the top of memory, execution from the reset vector.
_Avoid_: cold start, power-on

**Reset**:
The hardware reset of a running Machine: every Device reset and the CPU restarted through the reset vector, memory kept.
_Avoid_: reboot, restart

**Launch**:
How the Simulator started a Machine: the ROM, the image and its origin, the Disk, and the symbol files that go with them.
_Avoid_: boot config, session

**Restart**:
Doing the Launch again on the same Machine: memory cleared, images and symbol files read afresh, the same Program mode or ROM boot start. The Disk stays attached; anything loaded since the Launch is gone.
_Avoid_: reset, reload, reboot

**Stray interrupt**:
An interrupt entry (BRK, IRQ or NMI) whose vector holds `$0000`, meaning no handler was installed; the Simulator stops on it instead of jumping.
_Avoid_: unhandled interrupt, bad vector

**ROM**:
The Machine's read-only region at the top of memory, holding the ROM boot image.
_Avoid_: firmware, BIOS

**Character ROM**:
The read-only glyph image the Machine's future video Device draws text from.
_Avoid_: ROM, font ROM, charset

**Assembler**:
The program that turns 6502 assembly source into Machine-loadable bytes.

**Disassembler**:
The program that turns 6502 machine code back into assembly text.

**Section**:
A contiguous run of assembled bytes with an origin address.
_Avoid_: segment, block

**Fixup**:
A hole in a Section, filled with a symbol's value once that value is known.
_Avoid_: relocation, patch

**Linkage**:
Whether a symbol is global (visible beyond its own source) or local (private to it).
_Avoid_: scope, visibility, exported

**Cheap local label**:
An `@`-prefixed label whose name is only valid between two ordinary labels.
_Avoid_: local label, temporary label

**Toolchain**:
The Simulator, Assembler and Disassembler together.

**OS**:
The small operating system to be written in 6502 assembly and run on the Machine.
_Avoid_: kernel, firmware, ROM (when meaning the whole OS)
