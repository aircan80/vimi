package com.vibeminer.formats.db2;

import java.math.BigDecimal;
import java.util.Locale;

/** .NET invariant Single R formatting: shortest digits, general-format exponent policy. */
public final class SingleText {
    private SingleText() {}

    public static String format(float value) {
        if (!Float.isFinite(value)) {
            throw Db2Input.fail("UNSUPPORTED", "Nonfinite float");
        }
        if (value == 0) {
            return Float.floatToRawIntBits(value) < 0 ? "-0" : "0";
        }
        var exact = new BigDecimal((double) value);
        BigDecimal decimal = null;
        for (int precision = 1; precision <= 9; precision++) {
            var candidate =
                    exact.round(
                                    new java.math.MathContext(
                                            precision, java.math.RoundingMode.HALF_EVEN))
                            .stripTrailingZeros();
            if (Float.floatToRawIntBits(Float.parseFloat(candidate.toString()))
                    == Float.floatToRawIntBits(value)) {
                decimal = candidate;
                break;
            }
        }
        if (decimal == null) {
            throw new AssertionError("Single round-trip digits unavailable");
        }
        int exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent >= -4 && exponent < 9) {
            return decimal.toPlainString();
        }
        String digits = decimal.unscaledValue().abs().toString();
        String significand =
                (value < 0 ? "-" : "")
                        + digits.charAt(0)
                        + (digits.length() > 1 ? "." + digits.substring(1) : "");
        return significand
                + "E"
                + (exponent < 0 ? "-" : "+")
                + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
    }
}
