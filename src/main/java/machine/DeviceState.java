package machine;

import java.nio.file.Path;

/** A Device's state as one value: what the Front panel shows and what {@code info} prints, so the two never disagree. */
public sealed interface DeviceState {
    /** The Device's {@code info} line. */
    String describe();

    /** CONTROL, the bytes the program can still read (those before an EOF), whether EOF is queued, and the IRQ level. */
    record Console(int control, int waiting, boolean eof, boolean irq) implements DeviceState {
        @Override
        public String describe() {
            return String.format("Console: %d byte%s waiting, %s, IRQ %s", waiting, waiting == 1 ? "" : "s",
                    eof ? "EOF" : "no EOF", control != 0 ? "on" : "off");
        }
    }

    /** The CONTROL bits, RELOAD, the live count, the flag and the free-running cycle counter (32 bits, unsigned). */
    record Timer(boolean running, boolean periodic, boolean irqEnabled, boolean flag, int reload, int count, long cycles)
            implements DeviceState {
        @Override
        public String describe() {
            return String.format("Timer: %s, %s, IRQ %s, flag %s, reload $%04X", running ? "running" : "stopped",
                    periodic ? "periodic" : "one-shot", irqEnabled ? "on" : "off", flag ? "set" : "clear", reload);
        }
    }

    /**
     * The Disk image ({@code image} null with no media) and its size in Sectors, read-only, STATUS BUSY and DONE, ERROR,
     * the SECTOR and PAGE registers, and the last command latched with the cycles it has been in flight.
     */
    record Disk(Path image, int sectors, boolean readOnly, boolean busy, boolean done, int error, int sector, int page,
            int command, int elapsed) implements DeviceState {
        @Override
        public String describe() {
            return image == null ? "Disk: no media" : String.format("Disk: %s, %d bytes, %s, %s", image, sectors * 256,
                    readOnly ? "read-only" : "read-write", busy ? "busy" : "idle");
        }
    }
}
