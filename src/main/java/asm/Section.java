package asm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** A contiguous run of assembled bytes at a fixed origin, plus the Fixups still to fill. */
final class Section {
    final int origin;
    final Loc loc; // the *= that opened it
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final List<Fixup> fixups = new ArrayList<>();

    Section(int origin, Loc loc) {
        this.origin = origin;
        this.loc = loc;
    }

    int pc() {
        return origin + bytes.size();
    }
}
