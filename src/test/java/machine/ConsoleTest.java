package machine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;

/** The Console at $D000, driven through the Bus with input fed into its host-side queue. */
class ConsoleTest {
    private static final int DATA = 0xD000, STATUS = 0xD001, CONTROL = 0xD002;
    private final BlockingQueue<Integer> in = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final Machine m = new Machine(in, out);

    @Test
    void dataReadsReceivedBytesInFifoOrder() {
        in.add(0x41);
        in.add(0x42);
        in.add(0x43);

        assertEquals(0x41, m.bus.read(DATA));
        assertEquals(0x42, m.bus.read(DATA));
        assertEquals(0x43, m.bus.read(DATA));
    }

    @Test
    void dataReadWithNothingWaitingIsZeroAndChangesNothing() {
        assertEquals(0x00, m.bus.read(DATA));
        assertEquals(0x40, m.bus.read(STATUS)); // only ready to send

        in.add(0x41);
        assertEquals(0x41, m.bus.read(DATA)); // the empty read didn't eat a later byte
    }

    @Test
    void dataPeekShowsTheHeadWithoutTakingIt() {
        assertEquals(0x00, m.bus.peek(DATA));
        in.add(0x41);
        in.add(0x42);

        assertEquals(0x41, m.bus.peek(DATA));
        assertEquals(0x41, m.bus.peek(DATA));
        assertEquals(0xC0, m.bus.peek(STATUS));
        assertEquals(0x41, m.bus.read(DATA));
        assertEquals(0x42, m.bus.peek(DATA));
    }

    @Test
    void inputClosedShowsOnceTheQueueDrains() {
        in.add(0x41);
        in.add(Console.EOF);

        assertEquals(0xC0, m.bus.read(STATUS)); // byte waiting, not closed yet
        assertEquals(0x41, m.bus.read(DATA));
        assertEquals(0x60, m.bus.read(STATUS)); // closed, nothing waiting
        assertEquals(0x00, m.bus.read(DATA));
        assertEquals(0x00, m.bus.peek(DATA));
        assertEquals(0x60, m.bus.read(STATUS)); // stays closed
    }

    @Test
    void receiveIrqIsEnableAndByteWaiting() throws LoadException {
        m.loadProgram(new byte[] {(byte) 0xEA, (byte) 0xEA, (byte) 0xEA, (byte) 0xEA, (byte) 0xEA, (byte) 0xEA}, 0x0200); // NOPs, I set
        m.bus.write(CONTROL, 0xFF);
        assertEquals(0x80, m.bus.read(CONTROL)); // only bit 7 stored
        m.step();
        assertFalse(m.cpu.irqLine, "enabled, nothing waiting");

        in.add(0x41);
        m.step();
        assertTrue(m.cpu.irqLine, "enabled, byte waiting");

        m.bus.read(DATA);
        m.step();
        assertFalse(m.cpu.irqLine, "drops when DATA drains the queue");

        in.add(0x42);
        in.add(Console.EOF);
        m.step();
        assertTrue(m.cpu.irqLine);
        m.bus.write(CONTROL, 0x00);
        m.step();
        assertFalse(m.cpu.irqLine, "drops when the enable bit clears");

        m.bus.write(CONTROL, 0x80);
        m.bus.read(DATA);
        m.step();
        assertFalse(m.cpu.irqLine, "no IRQ on input closed");
    }

    @Test
    void dataWriteTransmitsEveryByteUntranslated() {
        for (int b : new int[] {0x68, 0x0A, 0x0D, 0x00, 0x80, 0xFF}) m.bus.write(DATA, b);

        assertArrayEquals(new byte[] {0x68, 0x0A, 0x0D, 0x00, (byte) 0x80, (byte) 0xFF}, out.toByteArray());
        assertEquals(0x00, m.bus.read(DATA), "no echo into the receive side");
    }

    @Test
    void statusAndUndecodedOffsetsIgnoreWritesAndReadFF() {
        m.bus.write(STATUS, 0xFF);
        m.bus.write(0xD003, 0x12);
        m.bus.write(0xD0FF, 0x12);

        assertEquals(0x40, m.bus.read(STATUS));
        assertEquals(0xFF, m.bus.read(0xD003));
        assertEquals(0xFF, m.bus.peek(0xD0FF));
        assertEquals(0, out.size());
    }

    @Test
    void resetClearsControlAndKeepsPendingInputAndEof() {
        m.bus.write(CONTROL, 0x80);
        in.add(0x41);
        in.add(Console.EOF);

        m.reset();

        assertEquals(0x00, m.bus.read(CONTROL));
        assertEquals(0xC0, m.bus.read(STATUS));
        assertEquals(0x41, m.bus.read(DATA));
        assertEquals(0x60, m.bus.read(STATUS));
    }

    @Test
    void stateShowsControlTheBytesWaitingBeforeEofEofAndTheIrqLevel() {
        assertEquals(new DeviceState.Console(0, 0, false, false), m.states().getFirst());
        assertEquals("Console: 0 bytes waiting, no EOF, IRQ off", m.describe().getFirst());

        in.add(0x41);
        in.add(Console.EOF);
        in.add(0x42); // behind EOF: never readable
        m.bus.write(CONTROL, 0x80);

        assertEquals(new DeviceState.Console(0x80, 1, true, true), m.states().getFirst());
        assertEquals("Console: 1 byte waiting, EOF, IRQ on", m.describe().getFirst());
    }
}
