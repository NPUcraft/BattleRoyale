package com.npucraft.battleroyale.admin;

import java.util.*;

/** Structural redaction. Support exports also omit inventory-bearing configuration altogether. */
public final class ConfigRedactor {
  private ConfigRedactor() {}

  public static Object redact(Object value) {
    return visit(value, 0);
  }

  private static Object visit(Object value, int depth) {
    if (value instanceof org.bukkit.configuration.ConfigurationSection section)
      value = section.getValues(false);
    if (depth > 32) return "<depth-limit>";
    if (value instanceof Map<?, ?> map) {
      var out = new LinkedHashMap<String, Object>();
      map.forEach(
          (k, v) -> {
            String key = String.valueOf(k);
            String lower = key.toLowerCase(Locale.ROOT);
            boolean secret =
                List.of(
                        "password",
                        "token",
                        "secret",
                        "credential",
                        "jdbc",
                        "username",
                        "inventory",
                        "ender",
                        "payload",
                        "data")
                    .stream()
                    .anyMatch(lower::contains);
            out.put(key, secret ? "<redacted>" : visit(v, depth + 1));
          });
      return out;
    }
    if (value instanceof List<?> list)
      return list.stream().limit(2048).map(v -> visit(v, depth + 1)).toList();
    if (value instanceof String text
        && (text.toLowerCase(Locale.ROOT).contains("jdbc:") || text.matches("(?s).*://[^/ ]+@.*")))
      return "<redacted>";
    return value;
  }
}
