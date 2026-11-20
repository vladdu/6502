package cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** CLI numbers: {@code 0x} or {@code $} hex, otherwise decimal. */
final class Numbers implements ITypeConverter<Integer> {
    @Override
    public Integer convert(String s) {
        try {
            int v = s.startsWith("$") ? Integer.parseInt(s.substring(1), 16)
                    : s.startsWith("0x") || s.startsWith("0X") ? Integer.parseInt(s.substring(2), 16)
                    : Integer.parseInt(s);
            if (v < 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new TypeConversionException("'" + s + "' is not a number (use 0x or $ for hex)");
        }
    }
}
