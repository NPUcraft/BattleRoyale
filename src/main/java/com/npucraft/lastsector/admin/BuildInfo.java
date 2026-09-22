package com.npucraft.lastsector.admin;

public final class BuildInfo {
  private BuildInfo() {}

  public static String text() {
    var properties = new java.util.Properties();
    try (var input = BuildInfo.class.getResourceAsStream("/lastsector-build.properties")) {
      if (input != null) properties.load(input);
    } catch (java.io.IOException ignored) {
    }
    return "Build: "
        + properties.getProperty("commit", "unknown")
        + " type="
        + properties.getProperty("type", "development");
  }
}
