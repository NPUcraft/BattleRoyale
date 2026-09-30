package com.npucraft.battleroyale.paper;

import com.npucraft.battleroyale.admin.*;
import com.npucraft.battleroyale.config.*;
import com.npucraft.battleroyale.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Captures server state on the server thread, serializes only safe snapshots on a bounded worker.
 */
public final class PaperDiagnostics implements AutoCloseable {
  private final JavaPlugin plugin;
  private final PluginRuntime runtime;
  private final FoundationService foundation;
  private boolean busy;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "BattleRoyale-support-io"));

  public PaperDiagnostics(JavaPlugin plugin, PluginRuntime runtime, FoundationService foundation) {
    this.plugin = plugin;
    this.runtime = runtime;
    this.foundation = foundation;
  }

  private DiagnosticsService.Check check(
      String name, DiagnosticsService.Status status, String detail) {
    return new DiagnosticsService.Check(name, status, detail);
  }

  public DiagnosticsService.Report collect() {
    var service = new DiagnosticsService();
    service.add(
        "storage",
        () ->
            check(
                "storage",
                runtime.storageDiagnostics().contains("connected=true")
                    ? DiagnosticsService.Status.OK
                    : DiagnosticsService.Status.ERROR,
                runtime.storageDiagnostics()));
    service.add(
        "recovery",
        () ->
            check(
                "recovery",
                runtime.recoveryReady() && runtime.recoveryDiagnostics().contains("orphanWorlds=0")
                    ? DiagnosticsService.Status.OK
                    : DiagnosticsService.Status.WARN,
                runtime.recoveryDiagnostics()));
    service.add(
        "maps",
        () -> {
          long valid =
              foundation.state().maps().all().stream()
                  .filter(m -> foundation.state().maps().isAvailable(m.id()))
                  .count();
          return check(
              "maps",
              valid > 0 && valid == foundation.state().maps().all().size()
                  ? DiagnosticsService.Status.OK
                  : DiagnosticsService.Status.WARN,
              "available=" + valid + " total=" + foundation.state().maps().all().size());
        });
    service.add(
        "economy",
        () ->
            check(
                "economy",
                runtime.progression().economy().available()
                    ? DiagnosticsService.Status.OK
                    : DiagnosticsService.Status.WARN,
                runtime.progression().economy().diagnostics()));
    service.add(
        "permanent-data",
        () ->
            check(
                "permanent-data",
                runtime.progression().diagnostics().contains("initialized=true")
                    ? DiagnosticsService.Status.OK
                    : DiagnosticsService.Status.WARN,
                runtime.progression().diagnostics()));
    service.add(
        "rooms",
        () ->
            check(
                "rooms",
                DiagnosticsService.Status.OK,
                "configured="
                    + runtime.rooms().rooms().size()
                    + " sessions="
                    + foundation.sessions().all().size()));
    service.add(
        "maintenance",
        () ->
            check(
                "maintenance",
                DiagnosticsService.Status.OK,
                String.join("; ", runtime.mapAdministration().summaries())));
    service.add(
        "runtime-root",
        () -> {
          var root = new com.npucraft.battleroyale.map.RuntimeLayout(plugin.getServer().getLevelDirectory(),
              com.npucraft.battleroyale.map.RuntimeLayout.GAME).runtimeRoot();
          try {
            AtomicFiles.safe(root);
            return check("runtime-root", DiagnosticsService.Status.OK, "ownership checks enabled");
          } catch (Exception e) {
            return check("runtime-root", DiagnosticsService.Status.ERROR, "Unsafe path");
          }
        });
    return service.collect();
  }

  public String perf() {
    var sessions = foundation.sessions().all();
    return "sessions="
        + sessions.size()
        + " players="
        + sessions.stream().mapToLong(s -> s.players().size()).sum()
        + " spectators="
        + runtime.spectators().registry().size()
        + " tasks="
        + plugin.getServer().getScheduler().getPendingTasks().stream()
            .filter(t -> t.getOwner() == plugin)
            .count()
        + "\n"
        + runtime.storageDiagnostics()
        + "\n"
        + runtime.progression().diagnostics()
        + "\nResources: "
        + runtime.matches().resourceCounts()
        + " pendingRestore="
        + runtime.pendingRestoreCount()
        + "\nTimings (nanoseconds, rolling window 128): "
        + PerformanceMetricsService.LIVE.timings()
        + "\nCounters: "
        + PerformanceMetricsService.LIVE.counters();
  }

  public String worlds() {
    StringBuilder out = new StringBuilder();
    for (var w : plugin.getServer().getWorlds()) {
      var path = w.getWorldFolder().toPath().toAbsolutePath().normalize();
      if (Set.of(com.npucraft.battleroyale.map.RuntimeLayout.GAME,
              com.npucraft.battleroyale.map.RuntimeLayout.MAINTENANCE).contains(w.getKey().getNamespace()))
        out.append("loaded ")
            .append(w.getName())
            .append(" path=")
            .append(path)
            .append(" players=")
            .append(w.getPlayerCount())
            .append('\n');
    }
    out.append(runtime.recoveryDiagnostics())
        .append('\n')
        .append(String.join("\n", runtime.mapAdministration().summaries()));
    return out.toString();
  }

  /** Capture Bukkit state now; inspect ownership files only on the diagnostics worker. */
  private java.util.concurrent.Callable<String> worldScan() {
    Path data = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    Path level = plugin.getServer().getLevelDirectory();
    var config = foundation.state().configuration();
    var loaded = plugin.getServer().getWorlds().stream()
        .map(w -> w.getWorldFolder().toPath().toAbsolutePath().normalize())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    return () -> {
      StringBuilder out = new StringBuilder();
      for (String namespace : List.of(com.npucraft.battleroyale.map.RuntimeLayout.GAME,
              com.npucraft.battleroyale.map.RuntimeLayout.MAINTENANCE)) {
        try {
          var files = com.npucraft.battleroyale.map.WorldFiles.paper262(data,
              new com.npucraft.battleroyale.map.RuntimeLayout(level, namespace),
              config.maps().stream().map(com.npucraft.battleroyale.map.MapTemplate::templatePath).toList(), List.of());
          for (var owned : files.ownedChildren(warning -> out.append("WARN ").append(warning).append('\n'))) {
            var world = owned.world();
            var marker = new Properties();
            Path markerPath = world.runtimePath().resolve(com.npucraft.battleroyale.map.WorldFiles.MARKER);
            AtomicFiles.safe(markerPath);
            try (var input = Files.newInputStream(markerPath)) { marker.load(input); }
            out.append("owned=true type=").append(marker.getProperty("type", "GAME"))
                .append(" session=").append(world.sessionId()).append(" room=").append(world.roomId())
                .append(" status=").append(owned.status())
                .append(" loaded=").append(loaded.contains(world.runtimePath()))
                .append(" path=").append(world.runtimePath()).append('\n');
          }
        } catch (Exception error) {
          out.append("WARN world scan unavailable: ").append(error.getClass().getSimpleName()).append('\n');
        }
      }
      return out.toString();
    };
  }

  public void debugWorlds(CommandSender sender) {
    if (!sender.hasPermission("battleroyale.admin")) throw new IllegalStateException(I18n.text(sender,"你没有管理权限 battleroyale.admin。","You need the battleroyale.admin permission."));
    if (busy) throw new IllegalStateException(I18n.text(sender,"诊断或备份操作正在进行，请稍候。","A diagnostic or backup operation is in progress. Please wait."));
    var scan = worldScan();
    String snapshot = worlds();
    busy = true;
    worker.execute(() -> {
      try {
        String result = snapshot + "\n" + scan.call();
        complete(() -> send(sender, result));
      } catch (Exception error) {
        complete(() -> send(sender, snapshot + "\nWARN ownership scan unavailable"));
      }
    });
  }

  public void command(CommandSender sender, String[] args) {
    String action = args[1].toLowerCase(Locale.ROOT);
    String permission =
        action.equals("config") ? "battleroyale.admin.config" : "battleroyale.admin.diagnostics";
    if (!sender.hasPermission(permission)) throw new IllegalStateException(I18n.text(sender,"缺少权限：%s","Missing permission: %s",permission));
    if (action.equals("diagnose")) {
      var report = collect();
      send(sender, I18n.text(sender,"总体状态：%s\n","Overall status: %s\n",I18n.state(sender,report.overall()))
          + report.checks().stream().map(c -> I18n.state(sender,c.status()) + " "
              + c.component() + I18n.text(sender,"：",": ") + c.detail())
              .collect(java.util.stream.Collectors.joining("\n")));
      runtime
          .progression()
          .manualReviewCount()
          .whenComplete(
              (count, error) ->
                  send(
                      sender,
                      error == null
                          ? I18n.text(sender,"%s：待人工复核的购买：%s","%s: Purchases awaiting manual review: %s",I18n.state(sender,count > 0 ? "WARN" : "OK"),count)
                          : I18n.text(sender,"暂时无法读取待复核购买数量。","The number of purchases awaiting review is temporarily unavailable.")));
      return;
    }
    if (action.equals("config")) {
      if (args.length != 3 || !Set.of("validate", "backup").contains(args[2]))
        throw new IllegalArgumentException(I18n.text(sender,"用法：/br admin config validate|backup","Usage: /br admin config validate|backup"));
      if (args[2].equals("validate")) {
        try {
          new ConfigMigrationService(plugin.getDataFolder().toPath()).validateVersions();
          var snapshot = new ConfigurationLoader(plugin.getDataFolder().toPath()).load();
          new MatchContentLoader(
                  plugin.getDataFolder().toPath(),
                  new NativeLootItems(),
                  new NativeItemSerializer()::item)
              .load(snapshot);
          ProgressionConfig.load(plugin, false);
          send(sender,I18n.text(sender,"全部配置文件校验通过，未修改任何文件。","All configuration files passed validation. No files were changed."));
        } catch (Exception e) {
          throw new IllegalStateException(I18n.text(sender,"配置校验失败：%s","Configuration validation failed: %s",I18n.error(sender,e.getMessage())));
        }
      } else backup(sender);
      return;
    }
    if (!action.equals("supportbundle"))
      throw new IllegalArgumentException(I18n.text(sender,"未知诊断命令。","Unknown diagnostic command."));
    if (busy) throw new IllegalStateException(I18n.text(sender,"诊断或备份操作正在进行，请稍候。","A diagnostic or backup operation is in progress. Please wait."));
    var scanWorlds = worldScan();
    var entries = new LinkedHashMap<String, String>();
    entries.put(
        "environment.txt",
        "BattleRoyale "
            + plugin.getPluginMeta().getVersion()
            + "\nJava "
            + System.getProperty("java.version")
            + "\nPaper "
            + plugin.getServer().getVersion()
            + "\n"
            + "Java target 25; Paper target 26.2; schema V2\n"
            + "Manual visual/large-client performance: not implied by this snapshot");
    entries.put("diagnostics.txt", collect().text());
    entries.put("performance.txt", perf());
    entries.put("worlds.txt", worlds());
    entries.put(
        "sessions.txt",
        foundation.sessions().all().stream()
            .map(
                s ->
                    "room="
                        + s.room().id()
                        + " state="
                        + s.state()
                        + " players="
                        + s.players().size()
                        + " map="
                        + s.selectedMap()
                            .map(m -> m.id() + " revision=" + m.metadataRevision())
                            .orElse("pending"))
            .collect(java.util.stream.Collectors.joining("\n")));
    entries.put(
        "privacy.txt",
        "No player inventories, chat, balances, raw recovery payloads or server logs are exported."
            + " Session/player identifiers omitted from session summary. Logs omitted because the"
            + " global server log is not a safe BattleRoyale-only source.");
    busy = true;
    worker.execute(
        () -> {
          var errors = new ArrayList<String>();
          for (String name :
              List.of(
                  "config.yml",
                  "rooms.yml",
                  "maps.yml",
                  "zones.yml",
                  "ranking.yml",
                  "lobby.yml",
                  "cosmetics.yml"))
            try {
              var yaml = new YamlConfiguration();
              Path path = plugin.getDataFolder().toPath().resolve(name);
              AtomicFiles.safe(path);
              yaml.load(path.toFile());
              entries.put(
                  name.replace(".yml", "") + "-redacted.txt",
                  new com.google.gson.GsonBuilder()
                      .setPrettyPrinting()
                      .create()
                      .toJson(ConfigRedactor.redact(yaml.getValues(false))));
            } catch (Exception e) {
              errors.add(name + ": " + e.getClass().getSimpleName());
            }
          try { entries.compute("worlds.txt", (key, value) -> value + "\n");
            entries.put("worlds.txt", entries.get("worlds.txt") + scanWorlds.call());
          } catch (Exception error) { errors.add("ownership scan: " + error.getClass().getSimpleName()); }
          entries.put("errors.txt", String.join("\n", errors));
          try {
            Path zip =
                SupportBundle.write(plugin.getDataFolder().toPath().resolve("support"), entries);
            complete(() -> send(sender,I18n.text(sender,"诊断包已生成：%s","Support bundle created: %s",zip.getFileName())));
          } catch (Exception e) {
            complete(() -> send(sender,I18n.text(sender,"诊断包生成失败：%s","Support bundle creation failed: %s",e.getClass().getSimpleName())));
          }
        });
  }

  private void backup(CommandSender sender) {
    if (busy) throw new IllegalStateException(I18n.text(sender,"诊断或备份操作正在进行，请稍候。","A diagnostic or backup operation is in progress. Please wait."));
    busy = true;
    worker.execute(
        () -> {
          try {
            Path target =
                plugin
                    .getDataFolder()
                    .toPath()
                    .resolve(
                        "config-backups/manual-"
                            + System.currentTimeMillis()
                            + "-"
                            + UUID.randomUUID());
            for (String name : ConfigMigrationService.FILES) {
              Path source = plugin.getDataFolder().toPath().resolve(name);
              AtomicFiles.safe(source);
              if (Files.exists(source))
                AtomicFiles.write(target.resolve(name), Files.readAllBytes(source));
            }
            complete(() -> send(sender,I18n.text(sender,"配置备份已生成：%s","Configuration backup created: %s",target.getFileName())));
          } catch (Exception e) {
            complete(
                () -> send(sender,I18n.text(sender,"配置备份失败：%s","Configuration backup failed: %s",e.getClass().getSimpleName())));
          }
        });
  }

  private void complete(Runnable callback) {
    if (plugin.isEnabled())
      plugin
          .getServer()
          .getScheduler()
          .runTask(
              plugin,
              () -> {
                busy = false;
                callback.run();
              });
  }

  private void send(CommandSender sender, String text) {
    sender.sendMessage(UiText.message(text));
  }

  public void close() {
    worker.shutdown();
  }
}
