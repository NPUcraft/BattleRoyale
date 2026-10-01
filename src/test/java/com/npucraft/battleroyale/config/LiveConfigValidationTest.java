package com.npucraft.battleroyale.config;

import com.npucraft.battleroyale.loot.LootItemResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Deployment gate: runs a real plugin data directory (typically a copy pulled from production) through the very
 * same {@link ConfigurationLoader} and {@link MatchContentLoader} the server boots with, so a broken config fails
 * here instead of disabling the plugin at startup.
 *
 * <p>Opt-in only: pass {@code -Dbr.live.config=<plugin data directory>}. A default test run skips it, so CI stays
 * independent of any local checkout.
 *
 * <p>The resolver is a catalog adapter rather than {@code NativeLootItems}: the production resolver touches the
 * ItemType / PotionEffectType registries, which do not exist in a bare API classpath. Material names are checked
 * here only for existence via {@code matchMaterial}; the authoritative is-item check is the packaged probe run
 * against a real server, and the {@code battleroyale:} presets are pinned to the production list.
 */
class LiveConfigValidationTest {
  /** Mirrors NativeLootItems.POTIONS plus the combat firework shortcut, so presets cannot drift silently. */
  private static final Set<String> NATIVE_PRESETS =
      Set.of(
          "combat_firework",
          "invisibility_potion",
          "fire_resistance_potion",
          "healing_potion",
          "harming_potion",
          "poison_potion",
          "splash_invisibility_potion",
          "splash_fire_resistance_potion",
          "splash_healing_potion",
          "splash_harming_potion",
          "splash_poison_potion");

  private static final List<String> UNRESOLVED = new ArrayList<>();

  private static final LootItemResolver<String> CATALOG =
      new LootItemResolver<>() {
        @Override
        public void validate(String key) {
          if (key.equals("battleroyale:combat_firework")) return;
          if (key.startsWith("battleroyale:")) {
            // A drinkable instant-damage potion only lets a player hurt itself; production rejects it too.
            if (key.equals("battleroyale:harming_potion"))
              throw new IllegalArgumentException("drinkable harming is rejected: " + key);
            if (!NATIVE_PRESETS.contains(key.substring("battleroyale:".length())))
              throw new IllegalArgumentException("Unknown native preset: " + key);
            return;
          }
          if (!key.startsWith("minecraft:"))
            throw new IllegalArgumentException("Unsupported loot provider: " + key);
          if (key.equals("minecraft:elytra"))
            throw new IllegalArgumentException("Elytra is not allowed in BattleRoyale loot");
          try {
            if (org.bukkit.Material.matchMaterial(key) == null) {
              UNRESOLVED.add(key);
              throw new IllegalArgumentException("Unknown loot item: " + key);
            }
          } catch (IllegalArgumentException | LinkageError failure) {
            if (failure instanceof IllegalArgumentException && UNRESOLVED.contains(key)) throw failure;
            // The bare API classpath cannot resolve material names at all; record it and let the probe decide.
            if (!UNRESOLVED.contains(key)) UNRESOLVED.add(key);
          }
        }

        @Override
        public String resolve(String key) {
          validate(key);
          return key;
        }
      };

  private static Path liveDirectory() {
    String value = System.getProperty("br.live.config", "").trim();
    assumeTrue(!value.isEmpty(), "pass -Dbr.live.config=<plugin data directory> to validate a real config set");
    Path directory = Path.of(value).toAbsolutePath().normalize();
    assumeTrue(Files.isDirectory(directory), () -> "not a directory: " + directory);
    return directory;
  }

  @Test
  void liveDirectoryLoadsThroughTheProductionLoaders() {
    Path directory = liveDirectory();
    List<String> stored = new ArrayList<>();

    MatchContent content =
        new MatchContentLoader(directory, CATALOG, item -> stored.add(String.valueOf(item)))
            .load(new ConfigurationLoader(directory, true).load());

    assertNotNull(content, "loader returned null");

    System.out.println("[live-config] directory=" + directory);
    System.out.println("[live-config] loot tables=" + new TreeSet<>(content.tables().keySet()));
    System.out.println("[live-config] maps=" + new TreeSet<>(content.maps().keySet()));
    System.out.println("[live-config] mapErrors=" + content.mapErrors());
    System.out.println("[live-config] ground-loot=" + content.groundLoot());
    System.out.println("[live-config] airdrops=" + content.airdrops());
    System.out.println("[live-config] mob-loot=" + content.mobLoot());
    System.out.println("[live-config] horses=" + content.horses());
    System.out.println("[live-config] region-quality=" + content.regionQuality());
    System.out.println("[live-config] auto-containers=" + content.autoContainers());
    System.out.println("[live-config] loadouts=" + new TreeSet<>(content.loadouts().keySet()));
    System.out.println("[live-config] unresolved materials=" + new TreeSet<>(UNRESOLVED));

    // A map declared in maps.yml must actually load; a per-map failure would otherwise only surface at match start.
    assertEquals(List.of(), List.copyOf(content.mapErrors().entrySet()), "map-data files failed to load");
  }
}
