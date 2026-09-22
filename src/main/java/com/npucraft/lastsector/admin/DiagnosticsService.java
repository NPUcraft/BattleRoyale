package com.npucraft.lastsector.admin;

import java.util.*;
import java.util.function.Supplier;

/** Independent components fail to ERROR without suppressing the rest of a support report. */
public final class DiagnosticsService {
  public enum Status {
    OK,
    WARN,
    ERROR
  }

  public record Check(String component, Status status, String detail) {}

  public record Report(List<Check> checks) {
    public Report {
      checks = List.copyOf(checks);
    }

    public Status overall() {
      return checks.stream().map(Check::status).max(Comparator.naturalOrder()).orElse(Status.OK);
    }

    public String text() {
      return "Overall: "
          + overall()
          + "\n"
          + checks.stream()
              .map(c -> c.status() + " " + c.component() + ": " + c.detail())
              .collect(java.util.stream.Collectors.joining("\n"));
    }
  }

  private final Map<String, Supplier<Check>> checks = new LinkedHashMap<>();

  public void add(String component, Supplier<Check> check) {
    checks.put(component, check);
  }

  public Report collect() {
    var result = new ArrayList<Check>();
    checks.forEach(
        (name, check) -> {
          try {
            result.add(check.get());
          } catch (Exception | LinkageError error) {
            result.add(
                new Check(
                    name, Status.ERROR, "Unavailable (" + error.getClass().getSimpleName() + ")"));
          }
        });
    return new Report(result);
  }
}
