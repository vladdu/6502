package asm;

import java.io.ByteArrayOutputStream;

/** A contiguous range at a fixed origin, filled in source order. */
final class Section {
    final String name;
    final int origin;
    final boolean reserved; // gives addresses, puts no bytes in the image
    final Loc loc; // the .defsection that declared it
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    Section(String name, int origin, boolean reserved, Loc loc) {
        this.name = name;
        this.origin = origin;
        this.reserved = reserved;
        this.loc = loc;
    }

    int pc() {
        return origin + bytes.size();
    }

    /** Name and range for messages: {@code Section code ($0200-$0209)}. */
    String describe() {
        return String.format("Section %s ($%04X-$%04X)", name, origin, pc() - 1);
    }
}
