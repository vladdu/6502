package asm;

import java.io.ByteArrayOutputStream;

/** A contiguous range at a fixed origin, filled in source order. */
final class Section {
    final String name; // null when opened by *=
    final int origin;
    final Loc loc; // the .defsection or *= that created it
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    Section(String name, int origin, Loc loc) {
        this.name = name;
        this.origin = origin;
        this.loc = loc;
    }

    int pc() {
        return origin + bytes.size();
    }

    /** Name and range for messages: {@code Section code ($0200-$0209)}, or just the range without a name. */
    String describe() {
        String range = String.format("$%04X-$%04X", origin, pc() - 1);
        return name == null ? "Section " + range : "Section " + name + " (" + range + ")";
    }
}
