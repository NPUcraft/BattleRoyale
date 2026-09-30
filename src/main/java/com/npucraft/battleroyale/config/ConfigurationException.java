package com.npucraft.battleroyale.config;
/** A configuration failure with source, path, rejected value and actionable reason. */
public final class ConfigurationException extends RuntimeException {
    public ConfigurationException(String file, String path, Object value, String reason) {
        super("Invalid configuration at " + file + ": " + path + " (value=" + value + "): " + reason);
    }
}

