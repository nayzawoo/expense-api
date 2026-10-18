package com.nayzawoo.expense.shared;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Money {

    public static final BigDecimal MAX_ABS = new BigDecimal("99999999999999999");

    private Money() {}

    public static BigDecimal parseSignedOpening(String raw) {
        BigDecimal value = parseCanonicalInteger(raw);
        if (value.abs().compareTo(MAX_ABS) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "MONEY_OUT_OF_RANGE");
        }
        return value;
    }

    public static BigDecimal parsePositivePrincipal(String raw) {
        BigDecimal value = parseCanonicalInteger(raw);
        if (value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(MAX_ABS) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "MONEY_OUT_OF_RANGE");
        }
        return value;
    }

    public static String format(BigDecimal value) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (BigDecimal.ZERO.compareTo(value) == 0) {
            return "0";
        }
        return value.toPlainString();
    }

    private static BigDecimal parseCanonicalInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MONEY");
        }
        String text = raw.trim();
        if (!text.matches("-?[0-9]+")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "MONEY_FRACTION_NOT_ALLOWED");
        }
        if (text.equals("-0")) {
            return BigDecimal.ZERO;
        }
        if (text.length() > 1 && text.charAt(0) == '0') {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MONEY");
        }
        if (text.length() > 2 && text.startsWith("-0")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MONEY");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_MONEY");
        }
    }
}
