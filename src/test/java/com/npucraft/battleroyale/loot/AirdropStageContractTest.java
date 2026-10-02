package com.npucraft.battleroyale.loot;

import com.npucraft.battleroyale.config.ConfigurationLoader;
import com.npucraft.battleroyale.config.MatchContent;
import com.npucraft.battleroyale.config.MatchContentLoader;
import com.npucraft.battleroyale.loot.AirdropRounds;
import com.npucraft.battleroyale.loot.AirdropSettings;
import com.npucraft.battleroyale.loot.LootItemResolver;
import com.npucraft.battleroyale.zone.Zone;
import com.npucraft.battleroyale.zone.ZonePhase;
import com.npucraft.battleroyale.zone.ZoneProfile;
import com.npucraft.battleroyale.zone.ZoneRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline re-runnable guardrail for the airdrop-cross-shrink-stage invariant.
 *
 * <p>Drives the production {@link ZoneRuntime} and {@link AirdropRounds} against the REAL zone profile and the REAL
 * airdrop parameters loaded from a live plugin data directory (typically a copy pulled from production). The PRIMARY
 * assertion is a per-round simulation: a drop issued in a shrink stage must still land inside that same stage with its
 * pinned destination inside the safe zone ({@code crossed==false && destStillSafe==true}). A secondary, conservative
 * formula precheck ({@code announce+fall < min(lead,wait)+shrink}) is kept as a fast guard; note the naive
 * {@code announce+fall < wait+shrink} is optimistic when the intended lead exceeds the stage wait, because production
 * issues a round at {@code remaining=min(lead, wait)} (the hard-coded {@code ANNOUNCEMENT_SECONDS} cap).
 *
 * <p>Opt-in only: pass {@code -Dbr.live.config=<plugin data directory>}. A default test run skips it, so CI stays
 * independent of any local checkout. Do not commit this class.
 */
class AirdropStageContractTest {
    private static final long SEED = 1L;
    private static final double STEP = 0.25;

    private static Path liveDirectory() {
        String value = System.getProperty("br.live.config", "").trim();
        assumeTrue(!value.isEmpty(), "pass -Dbr.live.config=<plugin data directory> to validate a real config set");
        Path directory = Path.of(value).toAbsolutePath().normalize();
        assumeTrue(Files.isDirectory(directory), () -> "not a directory: " + directory);
        return directory;
    }

    /** Mirror of LiveConfigValidationTest.CATALOG: a catalog adapter that resolves loot keys offline. */
    private static final Set<String> NATIVE_PRESETS = Set.of(
            "combat_firework", "invisibility_potion", "fire_resistance_potion", "healing_potion", "harming_potion",
            "poison_potion", "splash_invisibility_potion", "splash_fire_resistance_potion", "splash_healing_potion",
            "splash_harming_potion", "splash_poison_potion");
    private static final List<String> UNRESOLVED = new ArrayList<>();
    private static final LootItemResolver<String> CATALOG = new LootItemResolver<>() {
        public void validate(String key) {
            if (key.equals("battleroyale:combat_firework")) return;
            if (key.startsWith("battleroyale:")) {
                if (key.equals("battleroyale:harming_potion")) throw new IllegalArgumentException("drinkable harming rejected: " + key);
                if (!NATIVE_PRESETS.contains(key.substring("battleroyale:".length()))) throw new IllegalArgumentException("Unknown native preset: " + key);
                return;
            }
            if (!key.startsWith("minecraft:")) throw new IllegalArgumentException("Unsupported loot provider: " + key);
            if (key.equals("minecraft:elytra")) throw new IllegalArgumentException("Elytra not allowed: " + key);
            try {
                if (Material.matchMaterial(key) == null) { UNRESOLVED.add(key); throw new IllegalArgumentException("Unknown loot item: " + key); }
            } catch (IllegalArgumentException | LinkageError failure) {
                if (failure instanceof IllegalArgumentException && UNRESOLVED.contains(key)) throw failure;
                if (!UNRESOLVED.contains(key)) UNRESOLVED.add(key);
            }
        }
        public String resolve(String key) { validate(key); return key; }
    };

    private record Params(int announcementSeconds, int fallSeconds) {}
    private record RoundRow(int round, double issueSeconds, String issuePhase,
                            double destX, double destZ, double destHalf,
                            double landSeconds, int landStage, String landPhase,
                            boolean crossed, boolean destStillSafe,
                            long waitSeconds, long shrinkSeconds, int announce, int fall, boolean contractHolds) {}

    private static final class Loaded {
        final ZoneProfile profile;
        final AirdropSettings settings;
        final Params declared;
        Loaded(ZoneProfile profile, AirdropSettings settings, Params declared) { this.profile = profile; this.settings = settings; this.declared = declared; }
    }

    private Loaded load() throws Exception {
        Path dir = liveDirectory();
        var snapshot = new ConfigurationLoader(dir, true).load();
        MatchContent content = new MatchContentLoader(dir, CATALOG, item -> {}).load(snapshot);
        ZoneProfile profile = snapshot.zoneProfiles().stream().filter(p -> p.id().equals("default")).findFirst()
                .orElseThrow(() -> new IllegalStateException("no default zone profile in live config"));
        AirdropSettings settings = content.airdrops();
        YamlConfiguration loot = new YamlConfiguration();
        loot.load(dir.resolve("loot-tables.yml").toFile());
        int announce = loot.getInt("airdrops.announcement-seconds", -1);
        int fall = loot.getInt("airdrops.fall-seconds", -1);
        return new Loaded(profile, settings, new Params(announce, fall));
    }

    private static Zone initialZone(ZoneProfile profile) {
        int last = profile.initialSizes().size() - 1;
        double half = profile.initialSizes().get(last).halfSize();
        return new Zone(0, 0, half);
    }

    /** Simulate, through the production runtime, when each round is issued and where it lands relative to the stages. */
    private List<RoundRow> simulate(ZoneProfile profile, Zone initial, double announce, double fall) {
        ZoneRuntime probe = new ZoneRuntime(initial, profile, new Random(SEED), 0);
        AirdropRounds rounds = new AirdropRounds();
        double t = 0;
        List<Double> issues = new ArrayList<>();
        List<Integer> stages = new ArrayList<>();
        while (probe.phase() != ZonePhase.FINAL && t < 500000) {
            probe.update((long) (t * 1e9));
            var due = rounds.poll(probe.stageIndex(), probe.phase(), probe.remainingSeconds());
            if (due.isPresent()) { issues.add(t); stages.add(due.getAsInt()); }
            t += STEP;
        }
        List<RoundRow> rows = new ArrayList<>();
        for (int i = 0; i < issues.size(); i++) {
            double issueT = issues.get(i);
            int stage = stages.get(i);
            ZoneRuntime atIssue = new ZoneRuntime(initial, profile, new Random(SEED), 0);
            atIssue.update((long) (issueT * 1e9));
            Zone dest = atIssue.next() == null ? atIssue.current() : atIssue.next();
            double landT = issueT + announce + fall;
            ZoneRuntime atLand = new ZoneRuntime(initial, profile, new Random(SEED), 0);
            atLand.update((long) (landT * 1e9));
            boolean crossed = atLand.stageIndex() != stage;
            boolean safe = atLand.current().contains(dest.centerX(), dest.centerZ());
            var st = profile.stages().get(stage);
            long wait = st.waitDuration().toSeconds();
            long shrink = st.shrinkDuration().toSeconds();
            boolean holds = (announce + fall) < (wait + shrink);
            rows.add(new RoundRow(stage, issueT, atIssue.phase().name(), dest.centerX(), dest.centerZ(), dest.halfSize(),
                    landT, atLand.stageIndex(), atLand.phase().name(), crossed, safe, wait, shrink, (int) announce, (int) fall, holds));
        }
        return rows;
    }

    private static void printTable(String title, List<RoundRow> rows) {
        System.out.println("[airdrop-contract] " + title);
        System.out.println("[airdrop-contract] round issue@s issuePhase   dest(X,Z:half)         land@s landStage landPhase  crossed destSafe  wait+shrink  announce+fall  holds");
        for (var r : rows) {
            System.out.printf(
                    "[airdrop-contract] %5d %7.2f %-11s (%6.0f,%6.0f:%.0f) %7.2f %9d %-9s %-8s %-8s %3d+%-3d=%d  %d+%-3d=%d  %5s%n",
                    r.round, r.issueSeconds, r.issuePhase, r.destX, r.destZ, r.destHalf,
                    r.landSeconds, r.landStage, r.landPhase, r.crossed, r.destStillSafe,
                    r.waitSeconds, r.shrinkSeconds, r.waitSeconds + r.shrinkSeconds,
                    r.announce, r.fall, r.announce + r.fall, r.contractHolds);
        }
    }

    @Test
    void liveAirdropDeliveryStaysWithinEachShrinkStage() throws Exception {
        Loaded loaded = load();
        Zone initial = initialZone(loaded.profile);
        int announce = loaded.settings.announcementSeconds();
        int fall = loaded.settings.fallSeconds();
        System.out.println("[airdrop-contract] live config: AirdropSettings.announcementSeconds()=" + announce
                + " (hard-coded constant; loot-tables.yml declares announcement-seconds=" + loaded.declared.announcementSeconds
                + "), fall-seconds=" + fall + " (loot-tables.yml declares " + loaded.declared.fallSeconds + ")");
        List<RoundRow> rows = simulate(loaded.profile, initial, announce, fall);
        printTable("LIVE config (announce=" + announce + ", fall=" + fall + ")", rows);
        for (var r : rows) {
            // Secondary, conservative formula precheck. NOTE: when the intended lead (announce) exceeds the stage wait,
            // the round is actually issued at remaining=min(lead, wait), so the real window is min(lead, wait)+shrink;
            // the naive announce+fall < wait+shrink is optimistic in that regime.
            assertTrue(r.announce + r.fall < Math.min(r.announce, r.waitSeconds) + r.shrinkSeconds, String.format(
                    "stage %d: conservative guard announce+fall=%d < min(lead,wait)+shrink=%d+%d=%d",
                    r.round, r.announce + r.fall, Math.min(r.announce, r.waitSeconds), r.shrinkSeconds,
                    Math.min(r.announce, r.waitSeconds) + r.shrinkSeconds));
            // Primary assertion: the real per-round simulation must keep the drop inside the stage it was issued for,
            // and its pinned destination must still be inside the safe zone at landing time.
            assertTrue(!r.crossed && r.destStillSafe, String.format(
                    "stage %d: airdrop must land in its issue stage (crossed=%s) with dest still safe (destSafe=%s): "
                            + "issue=%.1fs land=%.1fs landStage=%d %s",
                    r.round, r.crossed, r.destStillSafe, r.issueSeconds, r.landSeconds, r.landStage, r.landPhase));
        }
    }

    @Test
    void historicalAnnouncement120ConfirmsEarlierRootCauseVerdict() throws Exception {
        Loaded loaded = load();
        Zone initial = initialZone(loaded.profile);
        // The configuration that was live when the root-cause analysis was hand-computed.
        int announce = 120, fall = 8;
        List<RoundRow> rows = simulate(loaded.profile, initial, announce, fall);
        printTable("HISTORICAL what-if (announce=120, fall=8) — reproduced to confirm/refute earlier hand computation", rows);
        // Hand-computed RHS were 150/105/90/85; the verdict was "120 violates stages 1/2/3, stage 0 (150) is safe".
        long[] rhs = {150, 105, 90, 85};
        for (int i = 0; i < rows.size(); i++) {
            var r = rows.get(i);
            assertEquals(rhs[i], r.waitSeconds + r.shrinkSeconds, "stage " + i + " wait+shrink (RHS) must match hand-computed value");
            // Real production issues a round at remaining<=ANNOUNCEMENT_SECONDS (60), so the effective lead is
            // min(120, wait). Every round then VIOLATES the per-round contract (clean in-stage landing):
            // rounds 0/1/2 cross the stage boundary (land in a later WAITING stage) though the dest is still safe,
            // and round 3 stays in stage 3 but lands FINAL/unsafe. This CONFIRMS (and sharpens) the earlier hand
            // verdict: stages 1/2/3 were predicted violated, and stage 0 also crosses in absolute time.
            assertTrue(r.crossed || !r.destStillSafe, String.format(
                    "stage %d: under announce=120,fall=8 the per-round contract must be violated (crossed=%s destSafe=%s, landed stage %d=%s)",
                    r.round, r.crossed, r.destStillSafe, r.landStage, r.landPhase));
        }
    }
}
