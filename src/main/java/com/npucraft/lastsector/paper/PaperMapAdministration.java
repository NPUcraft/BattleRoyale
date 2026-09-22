package com.npucraft.lastsector.paper;

import com.npucraft.lastsector.admin.*;
import com.npucraft.lastsector.config.*;
import com.npucraft.lastsector.loot.*;
import com.npucraft.lastsector.map.*;
import com.npucraft.lastsector.service.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Server-thread administration controller. IO uses one bounded worker and a drained completion
 * queue.
 */
public final class PaperMapAdministration implements Listener, AutoCloseable {
  private final JavaPlugin plugin;
  private final PluginRuntime runtime;
  private final FoundationService foundation;
  private final ConfigurationSnapshot configuration;
  private final MatchContent content;
  private final MapMaintenanceLocks locks = new MapMaintenanceLocks();
  private final MapMetadataStore store;
  private final MapValidationService validator = new MapValidationService();
  private final TemplateCommitService commits;
  private final WorldFiles files;
  private final PaperWorlds worlds;
  private final ThreadPoolExecutor worker;
  private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
  private final Map<String, Operation> operations = new LinkedHashMap<>();
  private final NamespacedKey toolKey;
  private final org.bukkit.scheduler.BukkitTask task;
  private volatile boolean closed;
  private boolean initialized;
  private final PerformanceMetricsService metrics = PerformanceMetricsService.LIVE;

  public PerformanceMetricsService metrics() {
    return metrics;
  }

  private static final class Operation {
    final MapMaintenanceLocks.Lease lease;
    final MapTemplate map;
    final CommandSender sender;
    GameWorld clone;
    MapEditorSession editor;
    Location first, second;
    String selection = "";
    boolean cancelled, saving, ready, closing, copyPending = true;
    int inFlight, completed, total, next, minChunkX, minChunkZ, width;
    long started = System.nanoTime();
    int announced = -1;

    Operation(MapMaintenanceLocks.Lease lease, MapTemplate map, CommandSender sender) {
      this.lease = lease;
      this.map = map;
      this.sender = sender;
    }
  }

  public PaperMapAdministration(
      JavaPlugin plugin,
      PluginRuntime runtime,
      FoundationService foundation,
      ConfigurationSnapshot configuration,
      MatchContent content) {
    this.plugin = plugin;
    this.runtime = runtime;
    this.foundation = foundation;
    this.configuration = configuration;
    this.content = content;
    Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
    store = new MapMetadataStore(root);
    commits = new TemplateCommitService(root);
    toolKey = new NamespacedKey(plugin, "map_editor_tool");
    try {
      files =
          new WorldFiles(
              root,
              root.resolve("maintenance-worlds"),
              plugin.getServer().getWorldContainer().toPath(),
              configuration.maps().stream().map(MapTemplate::templatePath).toList(),
              plugin.getServer().getWorlds().stream()
                  .map(w -> w.getWorldFolder().toPath())
                  .filter(
                      p ->
                          !p.toAbsolutePath()
                              .normalize()
                              .startsWith(configuration.settings().runtimeDirectory()))
                  .toList());
    } catch (Exception e) {
      throw new IllegalStateException("Invalid maintenance root", e);
    }
    worlds =
        new PaperWorlds(
            plugin.getServer(),
            new PaperPlayers(
                plugin.getServer(),
                configuration.settings().lobbyWorld(),
                new MessageService(plugin.getLogger())));
    worker =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64),
            r -> new Thread(r, "LastSector-maintenance-io"),
            new ThreadPoolExecutor.AbortPolicy());
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
    io(
        () -> {
          var results = new LinkedHashMap<String, Object>();
          for (var map : configuration.maps())
            try {
              commits.recover(map);
              if (content.mapErrors().containsKey(map.id()))
                throw new IllegalArgumentException(content.mapErrors().get(map.id()));
              var latest = store.overlay(map);
              var data = MapMetadata.initial(latest, content.maps().get(map.id()));
              results.put(
                  map.id(),
                  Map.entry(
                      latest,
                      validator.files(
                          validator.metadata(
                              latest, data, configuration, content.tables().keySet()),
                          latest,
                          files)));
            } catch (Exception e) {
              results.put(map.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
          for (var owned : files.ownedChildren(plugin.getLogger()::warning)) {
            Path path = owned.world().runtimePath();
            var marker = new Properties();
            try (var in = Files.newInputStream(path.resolve(WorldFiles.MARKER))) {
              marker.load(in);
            }
            if (Set.of("EDITOR", "MAINTENANCE").contains(marker.getProperty("type")))
              results.put("orphan:" + owned.world().sessionId(), owned);
          }
          return results;
        },
        (results, error) -> {
          if (error != null) {
            plugin
                .getLogger()
                .severe("Maintenance bootstrap failed: " + error.getClass().getSimpleName());
            return;
          }
          results.forEach(
              (id, value) -> {
                if (value instanceof WorldFiles.OwnedRuntime orphan) {
                  if (plugin.getServer().getWorlds().stream()
                      .noneMatch(
                          w ->
                              w.getWorldFolder()
                                  .toPath()
                                  .toAbsolutePath()
                                  .normalize()
                                  .equals(orphan.world().runtimePath())))
                    io(
                        () -> {
                          files.delete(orphan.world(), true);
                          return null;
                        },
                        (v, e) -> {
                          if (e != null)
                            plugin
                                .getLogger()
                                .warning(
                                    "Maintenance orphan retained: " + orphan.world().sessionId());
                        });
                } else if (value instanceof Map.Entry<?, ?> entry) {
                  var map = (MapTemplate) entry.getKey();
                  var report = (MapValidationService.Report) entry.getValue();
                  foundation.state().maps().replace(map);
                  if (!report.valid()) foundation.state().maps().unavailable(id, report.text());
                } else foundation.state().maps().unavailable(id, String.valueOf(value));
              });
          initialized = true;
        });
  }

  public boolean ready() {
    return initialized;
  }

  public boolean editing(UUID player) {
    return operations.values().stream()
        .anyMatch(
            o ->
                o.lease.kind() == MapMaintenanceLocks.Kind.EDITING
                    && o.lease.owner().equals(player));
  }

  public boolean locked(String map) {
    return !initialized || locks.busy(map);
  }

  public boolean busy() {
    return !operations.isEmpty()
        || worker.getActiveCount() > 0
        || !worker.getQueue().isEmpty()
        || !completions.isEmpty();
  }

  public List<String> summaries() {
    return operations.values().stream()
        .map(
            o ->
                o.map.id()
                    + " "
                    + o.lease.kind()
                    + " chunks="
                    + o.completed
                    + "/"
                    + o.total
                    + " ready="
                    + o.ready)
        .toList();
  }

  private Operation editor(Player player) {
    if(!player.hasPermission("lastsector.admin.map"))throw new IllegalStateException("Missing lastsector.admin.map permission");
    return operations.values().stream()
        .filter(o -> o.lease.owner().equals(player.getUniqueId()) && o.editor != null && !o.closing)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No active map editor"));
  }

  private <T> void io(Callable<T> work, java.util.function.BiConsumer<T, Throwable> finish) {
    if (closed && worker.isTerminated()) {
      try {
        finish.accept(work.call(), null);
      } catch (Exception e) {
        finish.accept(null, e);
      }
      return;
    }
    try {
      worker.execute(
          () -> {
            T value = null;
            Throwable error = null;
            try {
              value = work.call();
              if(closed && value instanceof GameWorld lateClone){files.delete(lateClone,true);value=null;throw new CancellationException("Shutdown discarded late maintenance clone");}
            } catch (Throwable e) {
              error = e;
            }
            T result = value;
            Throwable failure = error;
            completions.add(() -> finish.accept(result, failure));
          });
    } catch (RejectedExecutionException e) {
      finish.accept(null, e);
    }
  }

  private void message(CommandSender sender, String message) {
    sender.sendMessage(Component.text("[LastSector] " + message));
  }

  private void fail(Operation op, Throwable error) {
    message(
        op.sender,
        "Map operation failed: "
            + error.getClass().getSimpleName()
            + " "
            + Objects.toString(error.getMessage(), ""));
    cleanup(op);
  }

  public void command(CommandSender sender, String[] args) {
    if (!sender.hasPermission("lastsector.admin.map"))
      throw new IllegalStateException("Missing lastsector.admin.map permission");
    if (!initialized || !runtime.recoveryReady())
      throw new IllegalStateException("Map/recovery bootstrap is not ready");
    if (args.length < 3)
      throw new IllegalArgumentException(
          "Usage: /ls admin map"
              + " list|info|edit|editor|validate|pregenerate|save|discard|exit|area|ground|spectator|remove");
    String action = args[2].toLowerCase(Locale.ROOT);
    if (action.equals("list")) {
      foundation
          .state()
          .maps()
          .all()
          .forEach(
              m ->
                  message(
                      sender,
                      m.id()
                          + " revision="
                          + m.metadataRevision()
                          + " "
                          + (locks.busy(m.id())
                              ? "MAINTENANCE"
                              : foundation.state().maps().status(m.id()))));
      return;
    }
    if (Set.of("editor", "save", "discard", "exit", "area", "ground", "spectator", "remove", "name")
        .contains(action)) {
      if (!(sender instanceof Player player))
        throw new IllegalArgumentException("This command requires a player");
      var op = editor(player);
      if (op.saving) throw new IllegalStateException("Wait for the pending save");
      switch (action) {
        case "editor" -> menu(player, op);
        case "save" -> save(op);
        case "discard", "exit" -> {
          message(sender, "Editor discard requested");
          cleanup(op);
        }
        case "name" -> {
          if (args.length < 4) throw new IllegalArgumentException("name <display name>");
          op.editor.name(String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
        }
        case "area" -> {
          if (args.length != 7)
            throw new IllegalArgumentException("area <minX> <maxX> <minZ> <maxZ>");
          op.editor.area(
              new PlayableArea(
                  Double.parseDouble(args[3]),
                  Double.parseDouble(args[4]),
                  Double.parseDouble(args[5]),
                  Double.parseDouble(args[6])));
          report(op, false);
        }
        case "spectator" -> {
          Location l = player.getLocation();
          op.editor.spectator(
              new MapMetadata.SpectatorPosition(
                  l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
          message(sender, "Spectator fallback set");
        }
        case "remove" -> {
          if (args.length != 5 || !args[4].equals("confirm"))
            throw new IllegalArgumentException("remove <id> confirm");
          op.editor.remove(args[3], true);
          message(sender, "Metadata removed; block unchanged");
        }
        case "ground" -> {
          if (args.length != 9
              || op.first == null
              || op.second == null
              || !op.selection.equals("ground"))
            throw new IllegalArgumentException(
                "Select ground corners, then ground <id|new> <table> <chance> <minSpawn> <maxSpawn>"
                    + " <attempts>");
          var a = op.first;
          var z = op.second;
          String id = args[3].equals("new") ? "ground-" + UUID.randomUUID() : args[3];
          op.editor.ground(
              new LootArea(
                  id,
                  Math.min(a.getBlockX(), z.getBlockX()),
                  Math.max(a.getBlockX(), z.getBlockX()),
                  Math.min(a.getBlockY(), z.getBlockY()),
                  Math.max(a.getBlockY(), z.getBlockY()),
                  Math.min(a.getBlockZ(), z.getBlockZ()),
                  Math.max(a.getBlockZ(), z.getBlockZ()),
                  args[4],
                  Double.parseDouble(args[5]),
                  Integer.parseInt(args[6]),
                  Integer.parseInt(args[7]),
                  Integer.parseInt(args[8])));
          report(op, false);
        }
      }
      return;
    }
    if (args.length < 4) throw new IllegalArgumentException("Map id required");
    if (action.equals("pregenerate") && Set.of("cancel", "commit").contains(args[3])) {
      if (args.length < 5)
        throw new IllegalArgumentException("pregenerate cancel|commit <map> [confirm]");
      var op = operations.get(args[4]);
      if (op == null || op.lease.kind() != MapMaintenanceLocks.Kind.PREGENERATING)
        throw new IllegalStateException("No pregeneration for this map");
      if (args[3].equals("cancel")) {
        if (op.saving) throw new IllegalStateException("Commit already in progress");
        op.cancelled = true;
        message(op.sender, "Pregeneration cancellation requested");
        if (op.inFlight == 0) cleanup(op);
      } else {
        if (args.length != 6 || !args[5].equals("confirm"))
          throw new IllegalArgumentException("pregenerate commit <map> confirm");
        commit(op);
      }
      return;
    }
    var map =
        foundation
            .state()
            .maps()
            .find(args[3])
            .orElseThrow(() -> new IllegalArgumentException("Unknown map"));
    switch (action) {
      case "info" -> message(sender, map + " status=" + foundation.state().maps().status(map.id()));
      case "validate" -> {
        if (args.length == 5 && args[4].equals("--deep"))
          start(sender, map, MapMaintenanceLocks.Kind.VALIDATING_DEEP);
        else validate(sender, map);
      }
      case "edit" -> {
        if (!(sender instanceof Player p))
          throw new IllegalArgumentException("This command requires a player");
        if (runtime.rooms().participant(p.getUniqueId()).isPresent()
            || runtime.pendingRestore(p.getUniqueId())
            || runtime.spectators().registry().find(p.getUniqueId()).isPresent()
            || editing(p.getUniqueId())
            || runtime.loadouts().busy())
          throw new IllegalStateException("Leave rooms, spectators and editors first");
        runtime.progression().check(p.getUniqueId());
        start(sender, map, MapMaintenanceLocks.Kind.EDITING);
      }
      case "pregenerate" -> start(sender, map, MapMaintenanceLocks.Kind.PREGENERATING);
      default -> throw new IllegalArgumentException("Unknown map command");
    }
  }

  private void validate(CommandSender sender, MapTemplate map) {
    if (locks.busy(map.id())) throw new IllegalStateException("Map is under maintenance");
    var data = MapMetadata.initial(map, content.maps().get(map.id()));
    var report = validator.metadata(map, data, configuration, content.tables().keySet());
    io(
        () -> validator.files(report, map, files),
        (result, error) -> {
          if (error != null)
            message(sender, "Validation failed: " + error.getClass().getSimpleName());
          else publishReport(sender, result, true);
        });
  }

  private void publishReport(
      CommandSender sender, MapValidationService.Report report, boolean availability) {
    if (availability) {
      if (report.valid()) foundation.state().maps().available(report.map());
      else foundation.state().maps().unavailable(report.map(), report.text());
    }
    message(sender, report.text());
    Path target =
        plugin
            .getDataFolder()
            .toPath()
            .resolve("reports/map-" + report.map() + "-" + System.currentTimeMillis() + ".txt");
    String text = "LastSector " + plugin.getPluginMeta().getVersion() + "\n" + report.text();
    io(
        () -> {
          AtomicFiles.write(target, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          return null;
        },
        (v, e) -> {
          if (e != null) message(sender, "Report export failed");
        });
  }

  private void start(CommandSender sender, MapTemplate map, MapMaintenanceLocks.Kind kind) {
    var owner = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
    var lease = locks.acquire(map.id(), owner, kind);
    var op = new Operation(lease, map, sender);
    operations.put(map.id(), op);
    message(sender, "Preparing " + kind + " clone for " + map.id());
    io(
        () -> {
          long start = System.nanoTime();
          try {
            return files.copy(
                lease.token(),
                "maintenance",
                map,
                kind == MapMaintenanceLocks.Kind.EDITING ? "EDITOR" : "MAINTENANCE",
                owner);
          } finally {
            metrics.record(PerformanceMetricsService.Timer.WORLD_CLONE, System.nanoTime() - start);
          }
        },
        (clone, error) -> {
          op.copyPending = false;
          if (error != null) {
            fail(op, error);
            return;
          }
          op.clone = clone;
          if (closed || op.cancelled) {
            cleanup(op);
            return;
          }
          try {
            files.validateLoad(clone);
            worlds.load(clone);
            var world = plugin.getServer().getWorld(clone.worldName());
            if (kind == MapMaintenanceLocks.Kind.EDITING) {
              world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
              world.setGameRule(GameRule.DO_FIRE_TICK, false);
              world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
            }
            switch (kind) {
              case EDITING -> openEditor(op);
              case VALIDATING_DEEP -> deep(op);
              case PREGENERATING -> pregenerate(op);
            }
          } catch (Exception e) {
            fail(op, e);
          }
        });
  }

  private void openEditor(Operation op) {
    var player = plugin.getServer().getPlayer(op.lease.owner());
    if (player == null) {
      cleanup(op);
      return;
    }
    op.editor =
        new MapEditorSession(
            player.getUniqueId(),
            op.lease,
            MapMetadata.initial(op.map, content.maps().get(op.map.id())));
    runtime
        .isolateEditor(
            op.lease.token(),
            player.getUniqueId(),
            () -> !closed && !op.cancelled && player.isOnline())
        .whenComplete(
            (v, e) -> {
              if (e != null) {
                fail(op, e);
                return;
              }
              try {
                if (!player.teleport(
                    Objects.requireNonNull(plugin.getServer().getWorld(op.clone.worldName()))
                        .getSpawnLocation()))
                  throw new IllegalStateException("Editor teleport rejected");
                player.setGameMode(GameMode.CREATIVE);
                player.setAllowFlight(true);
                player.getInventory().clear();
                String[] keys = {"area", "container", "ground", "spectator", "inspect", "menu"};
                Material[] icons = {
                  Material.WOODEN_AXE,
                  Material.CHEST,
                  Material.STICK,
                  Material.ENDER_EYE,
                  Material.SPYGLASS,
                  Material.BOOK
                };
                for (int i = 0; i < keys.length; i++) {
                  var item = item(icons[i], keys[i]);
                  var meta = item.getItemMeta();
                  meta.getPersistentDataContainer()
                      .set(toolKey, PersistentDataType.STRING, keys[i]);
                  item.setItemMeta(meta);
                  player.getInventory().setItem(i, item);
                }
                message(player, "Editor ready; template is read-only. /ls admin map editor");
              } catch (Exception failure) {
                fail(op, failure);
              }
            });
  }

  private MapValidationService.Report report(Operation op, boolean publish) {
    var report =
        validator.metadata(op.map, op.editor.draft(), configuration, content.tables().keySet());
    if (publish) publishReport(op.sender, report, false);
    else message(op.sender, report.text());
    return report;
  }

  private void save(Operation op) {
    var report = report(op, false);
    if (!report.valid()) throw new IllegalStateException("Save refused: validation ERROR");
    op.saving = true;
    var draft = op.editor.draft();
    io(
        () -> store.save(op.map.id(), draft, draft.revision()),
        (saved, error) -> {
          op.saving = false;
          if (error != null) {
            message(
                op.sender,
                "Save failed; previous metadata retained: " + error.getClass().getSimpleName());
            return;
          }
          op.editor.published(saved);
          foundation.state().maps().replace(saved.apply(op.map));
          foundation.state().maps().available(op.map.id());
          message(
              op.sender,
              "Saved metadata revision "
                  + saved.revision()
                  + "; existing matches retain their snapshot");
          if (op.cancelled) cleanup(op);
        });
  }

  private static ItemStack item(Material type, String name) {
    var item = new ItemStack(type);
    var meta = item.getItemMeta();
    meta.displayName(Component.text(name));
    item.setItemMeta(meta);
    return item;
  }

  private static final class Menu implements InventoryHolder {
    final Inventory inventory;
    final Map<Integer, Runnable> actions = new HashMap<>();

    Menu(String title) {
      inventory = Bukkit.createInventory(this, 54, Component.text(title));
    }

    public Inventory getInventory() {
      return inventory;
    }

    void put(int slot, Material icon, String name, Runnable action) {
      inventory.setItem(slot, item(icon, name));
      actions.put(slot, action);
    }
  }

  private void menu(Player player, Operation op) {
    var menu = new Menu("Map Editor: " + op.map.id());
    menu.put(10, Material.MAP, "Map Info", () -> message(player, op.editor.draft().toString()));
    menu.put(12, Material.COMPASS, "Validate", () -> report(op, true));
    menu.put(14, Material.EMERALD, "Save", () -> save(op));
    menu.put(16, Material.BARRIER, "Discard / Exit", () -> cleanup(op));
    player.openInventory(menu.inventory);
  }

  private void containers(Player player, Operation op, org.bukkit.block.Block block, int page) {
    var menu = new Menu("Container LootTable");
    var tables = content.tables().keySet().stream().sorted().toList();
    for (int i = page * 45; i < Math.min(tables.size(), page * 45 + 45); i++) {
      String table = tables.get(i);
      menu.put(
          i - page * 45,
          Material.CHEST,
          table,
          () -> {
            String id = op.editor.container(block.getX(), block.getY(), block.getZ(), table);
            message(player, "Container " + id + " -> " + table);
            player.closeInventory();
          });
    }
    if (page > 0)
      menu.put(45, Material.ARROW, "Previous", () -> containers(player, op, block, page - 1));
    if ((page + 1) * 45 < tables.size())
      menu.put(53, Material.ARROW, "Next", () -> containers(player, op, block, page + 1));
    var point =
        op.editor.draft().loot().containers().stream()
            .filter(p -> p.x() == block.getX() && p.y() == block.getY() && p.z() == block.getZ())
            .findFirst();
    point.ifPresent(
        p ->
            menu.put(
                49,
                Material.BARRIER,
                "Remove (confirm next)",
                () -> {
                  var confirm = new Menu("Confirm metadata removal");
                  confirm.put(
                      20,
                      Material.RED_CONCRETE,
                      "Confirm remove " + p.id(),
                      () -> {
                        op.editor.remove(p.id(), true);
                        player.closeInventory();
                      });
                  confirm.put(
                      24,
                      Material.GREEN_CONCRETE,
                      "Cancel",
                      () -> containers(player, op, block, page));
                  player.openInventory(confirm.inventory);
                }));
    player.openInventory(menu.inventory);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent e) {
    if (!editing(e.getPlayer().getUniqueId())) return;
    e.setCancelled(true);
    if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
    var stack = e.getItem();
    if (stack == null || !stack.hasItemMeta()) return;
    String tool =
        stack.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    if (tool == null) return;
    var player = e.getPlayer();
    try {
      var op = editor(player);
      if (op.saving) return;
      var block = e.getClickedBlock();
      Location location = block == null ? player.getLocation() : block.getLocation();
      switch (tool) {
        case "menu" -> menu(player, op);
        case "container" -> {
          if (block == null || !(block.getState() instanceof Container))
            throw new IllegalArgumentException("Point at a supported block container");
          containers(player, op, block, 0);
        }
        case "spectator" -> {
          var l = player.getLocation();
          op.editor.spectator(
              new MapMetadata.SpectatorPosition(
                  l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
          message(player, "Spectator point set");
        }
        case "area", "ground" -> {
          if (!op.selection.equals(tool)) {
            op.first = null;
            op.second = null;
            op.selection = tool;
          }
          if (e.getAction().isLeftClick()) op.first = location;
          else if (e.getAction().isRightClick()) op.second = location;
          if (op.first != null && op.second != null && tool.equals("area")) {
            op.editor.area(
                new PlayableArea(
                    Math.min(op.first.getX(), op.second.getX()),
                    Math.max(op.first.getX(), op.second.getX()),
                    Math.min(op.first.getZ(), op.second.getZ()),
                    Math.max(op.first.getZ(), op.second.getZ())));
            var a = op.editor.draft().playableArea();
            message(
                player,
                "width="
                    + (a.maxX() - a.minX())
                    + " depth="
                    + (a.maxZ() - a.minZ())
                    + " center="
                    + (a.minX() + a.maxX()) / 2
                    + ","
                    + (a.minZ() + a.maxZ()) / 2);
            report(op, false);
          } else
            message(
                player,
                tool
                    + " corner selected; ground: /ls admin map ground new <table> <chance> <min>"
                    + " <max> <attempts>");
        }
        case "inspect" -> {
          var d = op.editor.draft();
          message(player, "Area: " + d.playableArea());
          d.loot().containers().stream()
              .filter(
                  p ->
                      p.x() == location.getBlockX()
                          && p.y() == location.getBlockY()
                          && p.z() == location.getBlockZ())
              .forEach(p -> message(player, p.toString()));
          d.loot().areas().stream()
              .filter(
                  a ->
                      location.getX() >= a.minX()
                          && location.getX() <= a.maxX()
                          && location.getZ() >= a.minZ()
                          && location.getZ() <= a.maxZ())
              .forEach(a -> message(player, a.toString()));
          report(op, false);
        }
      }
    } catch (RuntimeException error) {
      message(player, error.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!editing(e.getWhoClicked().getUniqueId())) return;
    e.setCancelled(true);
    if (e.getView().getTopInventory().getHolder() instanceof Menu menu
        && e.getClick() == ClickType.LEFT) {
      var action = menu.actions.get(e.getRawSlot());
      if (action != null)
        completions.add(
            () -> {
              if (e.getWhoClicked() instanceof Player p && editing(p.getUniqueId()))
                try {
                  if (!editor(p).saving) action.run();
                } catch (RuntimeException error) {
                  message(p, error.getMessage());
                }
            });
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (editing(e.getWhoClicked().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void drop(PlayerDropItemEvent e) {
    if (editing(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void swap(PlayerSwapHandItemsEvent e) {
    if (editing(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void place(BlockPlaceEvent e) {
    if (editing(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void breakBlock(BlockBreakEvent e) {
    if (editing(e.getPlayer().getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player p && editing(p.getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void pickup(EntityPickupItemEvent e) {
    if (e.getEntity() instanceof Player p && editing(p.getUniqueId())) e.setCancelled(true);
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    operations.values().stream()
        .filter(
            o ->
                o.lease.kind() == MapMaintenanceLocks.Kind.EDITING
                    && o.lease.owner().equals(e.getPlayer().getUniqueId()))
        .findFirst()
        .ifPresent(
            o -> {
              o.cancelled = true;
              if (!o.saving) cleanup(o);
            });
  }

  private void deep(Operation op) {
    var data = MapMetadata.initial(op.map, content.maps().get(op.map.id()));
    var findings =
        new ArrayList<>(
            validator.metadata(op.map, data, configuration, content.tables().keySet()).findings());
    var world = Objects.requireNonNull(plugin.getServer().getWorld(op.clone.worldName()));
    var checks = new ArrayDeque<Runnable>();
    for (var p : data.loot().containers())
      checks.add(
          () -> {
            if (p.y() < world.getMinHeight()
                || p.y() >= world.getMaxHeight()
                || !(world.getBlockAt(p.x(), p.y(), p.z()).getState() instanceof Container))
              findings.add(
                  new MapValidationService.Finding(
                      MapValidationService.Severity.ERROR, "Not a supported container: " + p.id()));
          });
    for (var a : data.loot().areas())
      checks.add(
          () -> {
            boolean safe = false;
            for (int i = 0; i < 8; i++) {
              int x = a.minX() + (int) ((long) (a.maxX() - a.minX()) * i / 7),
                  z = a.minZ() + (int) ((long) (a.maxZ() - a.minZ()) * (7 - i) / 7);
              int y = world.getHighestBlockYAt(x, z);
              if (y >= a.minY()
                  && y <= a.maxY()
                  && world.getBlockAt(x, y, z).getType().isSolid()
                  && world.getBlockAt(x, y + 1, z).isPassable()) safe = true;
            }
            if (!safe)
              findings.add(
                  new MapValidationService.Finding(
                      MapValidationService.Severity.WARNING,
                      "No safe surface in 8 bounded samples: " + a.id()));
          });
    if (data.spectator() != null)
      checks.add(
          () -> {
            var p = data.spectator();
            if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight())
              findings.add(
                  new MapValidationService.Finding(
                      MapValidationService.Severity.ERROR, "Spectator Y outside world"));
            else world.getChunkAt((int) Math.floor(p.x()) >> 4, (int) Math.floor(p.z()) >> 4);
          });
    deepChecks.put(
        op,
        () -> {
          if (!checks.isEmpty()) checks.remove().run();
          else {
            deepChecks.remove(op);
            publishReport(
                op.sender,
                new MapValidationService.Report(
                    op.map.id(), data.revision(), java.time.Instant.now(), findings),
                true);
            cleanup(op);
          }
        });
  }

  private final Map<Operation, Runnable> deepChecks = new HashMap<>();

  private void pregenerate(Operation op) {
    var a = op.map.playableArea();
    op.minChunkX = (int) Math.floor(a.minX() / 16);
    op.minChunkZ = (int) Math.floor(a.minZ() / 16);
    op.width = (int) Math.floor(a.maxX() / 16) - op.minChunkX + 1;
    long depth = (long) Math.floor(a.maxZ() / 16) - op.minChunkZ + 1;
    long total = Math.multiplyExact((long) op.width, depth);
    if (total < 1 || total > 1_000_000)
      throw new IllegalArgumentException("Pregeneration limit: 1,000,000 chunks");
    op.total = (int) total;
  }

  private void tick() {
    for (int i = 0; i < 128; i++) {
      var next = completions.poll();
      if (next == null) break;
      try {
        next.run();
      } catch (Exception e) {
        plugin.getLogger().severe("Maintenance callback failed: " + e.getClass().getSimpleName());
      }
    }
    if (closed) return;
    for (var entry : List.copyOf(deepChecks.entrySet()))
      try {
        entry.getValue().run();
      } catch (Exception e) {
        fail(entry.getKey(), e);
      }
    int concurrency =
        Math.max(
            1,
            Math.min(
                32,
                plugin
                    .getConfig()
                    .getInt("map-maintenance.pregeneration.max-concurrent-chunks", 8)));
    for (var op : List.copyOf(operations.values())) {
      if (op.closing || op.clone == null) continue;
      if (op.lease.kind() == MapMaintenanceLocks.Kind.PREGENERATING
          && !op.ready
          && !op.saving
          && op.total > 0) {
        if (op.cancelled) {
          if (op.inFlight == 0) cleanup(op);
          continue;
        }
        var world = plugin.getServer().getWorld(op.clone.worldName());
        while (op.inFlight < concurrency && op.next < op.total) {
          int index = op.next++;
          op.inFlight++;
          world
              .getChunkAtAsync(
                  op.minChunkX + index % op.width, op.minChunkZ + index / op.width, true)
              .whenComplete(
                  (chunk, error) ->
                      completions.add(
                          () -> {
                            if (closed) return;
                            op.inFlight--;
                            if (error != null) {
                              op.cancelled = true;
                              message(op.sender, "Chunk generation failed; template untouched");
                            } else {
                              op.completed++;
                              chunk.getWorld().unloadChunkRequest(chunk.getX(), chunk.getZ());
                            }
                          }));
        }
        int percent = op.completed * 100 / op.total;
        if (percent / 5 > op.announced) {
          op.announced = percent / 5;
          message(
              op.sender,
              "Pregeneration "
                  + op.completed
                  + "/"
                  + op.total
                  + " ("
                  + percent
                  + "%) elapsed="
                  + (System.nanoTime() - op.started) / 1_000_000_000L
                  + "s");
        }
        if (op.completed == op.total) {
          op.ready = true;
          message(
              op.sender,
              "Ready; /ls admin map pregenerate commit " + op.map.id() + " confirm (or cancel)");
        }
      }
    }
    if (plugin.getServer().getCurrentTick() % 20 == 0) preview();
  }

  private void preview() {
    int remaining = 256;
    for (var op : operations.values()) {
      if (op.editor == null || op.clone == null) continue;
      var player = plugin.getServer().getPlayer(op.lease.owner());
      if (player == null || !player.getWorld().getName().equals(op.clone.worldName())) continue;
      var area = op.editor.draft().playableArea();
      var l = player.getLocation();
      int sent = 0;
      for (int i = -24; i <= 24 && sent < 64 && remaining > 0; i += 2) {
        double x = l.getX() + i, z = l.getZ() + i;
        double[][] points = {
          {x, area.minZ()}, {x, area.maxZ()}, {area.minX(), z}, {area.maxX(), z}
        };
        for (var p : points)
          if (sent < 64
              && remaining > 0
              && Math.abs(p[0] - l.getX()) <= 32
              && Math.abs(p[1] - l.getZ()) <= 32) {
            player.spawnParticle(Particle.END_ROD, p[0], l.getY(), p[1], 1);
            sent++;
            remaining--;
          }
      }
      for (var a : op.editor.draft().loot().areas())
        for (double x : new double[] {a.minX(), a.maxX()})
          for (double z : new double[] {a.minZ(), a.maxZ()})
            if (sent < 64
                && remaining > 0
                && Math.abs(x - l.getX()) <= 32
                && Math.abs(z - l.getZ()) <= 32) {
              player.spawnParticle(
                  Particle.END_ROD, x, Math.max(a.minY(), Math.min(a.maxY(), l.getY())), z, 1);
              sent++;
              remaining--;
            }
      metrics.add(PerformanceMetricsService.Counter.PARTICLE_SAMPLES, sent);
    }
  }

  private void commit(Operation op) {
    if (!op.ready || op.inFlight != 0 || op.saving || op.cancelled)
      throw new IllegalStateException("Pregeneration is not ready to commit");
    var world = Objects.requireNonNull(plugin.getServer().getWorld(op.clone.worldName()));
    world.save();
    if (!plugin.getServer().unloadWorld(world, true))
      throw new IllegalStateException("Maintenance world unload refused");
    op.saving = true;
    io(
        () -> commits.commit(op.map, op.clone, files, true),
        (backup, error) -> {
          op.saving = false;
          if (error != null) {
            fail(op, error);
            return;
          }
          op.clone = null;
          foundation.state().maps().available(op.map.id());
          message(op.sender, "Template committed; backup=" + backup.getFileName());
          finish(op);
        });
  }

  private void cleanup(Operation op) {
    if (op.closing) return;
    op.cancelled = true;
    if (op.copyPending || op.saving || op.inFlight > 0) return;
    op.closing = true;
    deepChecks.remove(op);
    runtime.restoreEditor(op.lease.token());
    var player = plugin.getServer().getPlayer(op.lease.owner());
    if (player != null) player.closeInventory();
    if (op.clone == null) {
      finish(op);
      return;
    }
    try {
      worlds.unload(op.clone);
    } catch (Exception error) {
      op.closing = false;
      message(op.sender, "Cleanup retained owned world: " + error.getMessage());
      return;
    }
    var clone = op.clone;
    io(
        () -> {
          files.delete(clone, true);
          return null;
        },
        (v, e) -> {
          if (e != null)
            message(op.sender, "Owned cleanup directory retained for startup inspection");
          finish(op);
        });
  }

  private void finish(Operation op) {
    operations.remove(op.map.id(), op);
    locks.release(op.lease);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    HandlerList.unregisterAll(this);
    for (var op : List.copyOf(operations.values())) {
      op.cancelled = true;
      op.inFlight = 0;
      cleanup(op);
    }
    task.cancel();
    worker.shutdown();
    try {
      if (!worker.awaitTermination(30, TimeUnit.SECONDS))
        plugin.getLogger().warning("Maintenance IO retained for recovery");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    for (Runnable next; (next = completions.poll()) != null; )
      try {
        next.run();
      } catch (Exception e) {
        plugin
            .getLogger()
            .warning("Maintenance shutdown callback retained: " + e.getClass().getSimpleName());
      }
  }
}
