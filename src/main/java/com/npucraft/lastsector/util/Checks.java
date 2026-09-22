package com.npucraft.lastsector.util;

/** Shared validation for domain value objects. */
public final class Checks {
    private Checks() {}
    public static String text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
    public static double finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return value;
    }
}

