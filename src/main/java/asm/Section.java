package asm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** A contiguous run of assembled bytes at a fixed origin, plus the Fixups still to fill. */
final class Section {
    final int origin;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final List<Fixup> fixups = new ArrayList<>();

    Section(int origin) {
        this.origin = origin;
    }

    int pc() {
        return origin + bytes.size();
    }
}
