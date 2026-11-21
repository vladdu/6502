package gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConsoleKeysTest {
    private final ConsoleKeys keys = new ConsoleKeys();

    @Test
    void controlAndEditingKeysBecomeTheirAsciiBytes() {
        assertArrayEquals(new byte[] {0x03}, keys.typed('\u0003')); // Ctrl+C
        assertArrayEquals(new byte[] {0x09}, keys.typed('\t'));
        assertArrayEquals(new byte[] {0x08}, keys.typed('\b'));
        assertArrayEquals(new byte[] {0x1B}, keys.typed('\u001B'));
        assertArrayEquals(new byte[] {0x0A}, keys.typed('\n')); // Enter
        assertArrayEquals(new byte[] {'a'}, keys.typed('a'));
    }

    @Test
    void nonAsciiBecomesUtf8AndASurrogatePairOneCharacter() {
        assertArrayEquals(new byte[] {(byte) 0xC3, (byte) 0xA9}, keys.typed('é'));
        assertArrayEquals(new byte[0], keys.typed('\uD83D'));
        assertArrayEquals(new byte[] {(byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80}, keys.typed('\uDE00'));
    }

    @Test
    void ctrlRightBracketAndKeysWithNoCharacterNeverBecomeBytes() {
        assertArrayEquals(new byte[0], keys.typed('\u001D'));
        assertArrayEquals(new byte[0], keys.typed('￿')); // KeyEvent.CHAR_UNDEFINED: F6 and other function keys
    }
}
