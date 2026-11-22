# LEDs Device

The Machine does not get a Device of plain on/off LEDs driven by a register and shown in the Front panel.

## Why this is out of scope

It costs too much for what it gives.

The gain is small: one byte shown as eight indicators. The Front panel's Memory views already show a byte changing
while a program runs, and the Console, Timer and Disk already give programs hardware registers to drive. A fast
program would not even look like blinking LEDs: the Front panel samples every 50 ms, so a counter shows unrelated
values unless the Machine is throttled to about 100 cycles per second or the program has delay loops.

The cost is not small. The Machine ticks and polls every Device after every Step, so when this was triaged a fifth
Device that did nothing made an unthrottled benchmark about 17% slower without the program ever touching it. It
would also add a register, a `DeviceState`, a pane section, `info` output and their docs and tests.

This does not reject the video and sound Devices the I/O region reserves space for.

## Prior requests

- #62: "LEDs Device: a few LEDs driven by a memory address, shown in the Front panel"
