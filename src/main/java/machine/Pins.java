package machine;

import java.util.List;

/** The CPU's IRQ and NMI wired-ORs as the names of the sources asserting each; a pin is high while its list is not empty. */
public record Pins(List<String> irq, List<String> nmi) {}
