package cli;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/** A speed in decimal MHz ({@code --speed}, the Monitor's {@code speed}) to Hz; at least 0.5 Hz. */
final class Speed implements ITypeConverter<Long> {
    @Override
    public Long convert(String mhz) {
        double v;
        try {
            v = Double.parseDouble(mhz);
        } catch (NumberFormatException _) {
            v = Double.NaN;
        }
        if (!(v * 1e6 >= 0.5)) throw new TypeConversionException(mhz + ": speed must be a positive number of MHz"); // also refuses NaN
        return Math.round(v * 1e6);
    }
}
