package com.npucraft.battleroyale.zone;
import com.npucraft.battleroyale.util.Checks;
import java.time.Duration;
import com.npucraft.battleroyale.map.MapTemplate;
import java.util.*;
import java.util.random.RandomGenerator;
/** Immutable validated zone configuration and player-count bucket selection. */
public record ZoneProfile(String id, List<InitialSize> initialSizes, List<Stage> stages,
                          double stageReferenceHalfSize, Map<String, InitialZoneCenters> initialCenters) {
    /** Existing configurations retain absolute targets and their original recovery hash. */
    public ZoneProfile(String id, List<InitialSize> initialSizes, List<Stage> stages) {
        this(id, initialSizes, stages, 0, Map.of());
    }
    public ZoneProfile {
        Checks.text(id, "id");
        initialSizes = List.copyOf(initialSizes); stages = List.copyOf(stages);
        if (initialSizes.isEmpty() || stages.isEmpty()) throw new IllegalArgumentException("Zone lists must not be empty");
        Checks.finite(stageReferenceHalfSize, "stageReferenceHalfSize");
        if (stageReferenceHalfSize != 0 && stageReferenceHalfSize < 128)
            throw new IllegalArgumentException("stageReferenceHalfSize must be 0 or >= 128");
        var centers = new TreeMap<String, InitialZoneCenters>();
        initialCenters.forEach((mapId, settings) -> { Checks.text(mapId, "mapId"); centers.put(mapId, Objects.requireNonNull(settings)); });
        initialCenters = Collections.unmodifiableMap(centers);
        int previous = 0;
        for (InitialSize size : initialSizes) {
            if (size.maxPlayers() <= previous) throw new IllegalArgumentException("max-players thresholds must strictly increase");
            previous = size.maxPlayers();
        }
        double previousSize = stageReferenceHalfSize > 0 ? stageReferenceHalfSize : initialSizes.stream().mapToDouble(InitialSize::halfSize).min().orElseThrow();
        for (Stage stage : stages) {
            if (stage.targetHalfSize() >= previousSize) throw new IllegalArgumentException("stage target-half-size must strictly decrease");
            previousSize = stage.targetHalfSize();
        }
    }
    /** Chooses only the initial square; later targets retain unrestricted contained random centers. */
    public Zone initialZone(MapTemplate map, int actualPlayers, RandomGenerator random) {
        return initialZone(map, actualPlayers, random, null);
    }
    /** The optional region ID was selected once from the opening vote; it never constrains later circles. */
    public Zone initialZone(MapTemplate map, int actualPlayers, RandomGenerator random, String regionId) {
        double half = initialHalfSize(actualPlayers);
        var centers = initialCenters.get(map.id());
        if (centers == null && regionId != null) throw new IllegalArgumentException("Named initial region is not configured for map: " + map.id());
        return centers == null ? ZoneGeometry.initial(map.playableArea(), half, random)
                : centers.choose(map.playableArea(), half, random, regionId);
    }
    /** Validate all initial buckets reachable by this room, including nonmonotonic custom buckets. */
    public void validateInitialCenters(MapTemplate map, int roomMaximum) {
        double half = largestReachableHalfSize(roomMaximum);
        var centers = initialCenters.get(map.id());
        if (centers != null) centers.validate(map.playableArea(), half);
    }
    /** Resolve from the immutable saved initial square, never the current population or remaining circle. */
    public ZoneProfile resolved(double initialHalfSize) {
        Checks.finite(initialHalfSize, "initialHalfSize");
        if (initialHalfSize <= 0) throw new IllegalArgumentException("initialHalfSize > 0 required");
        if (stageReferenceHalfSize == 0) return this;
        if (initialHalfSize < 128) throw new IllegalArgumentException("scaled initialHalfSize >= 128 required");
        if (initialHalfSize == stageReferenceHalfSize) return this;
        var scaled = stages.stream().map(stage -> new Stage(stage.waitDuration(), stage.shrinkDuration(),
                stage.targetHalfSize() / stageReferenceHalfSize * initialHalfSize,
                stage.baseDamagePerSecond(), stage.extraDamagePerBlock(), stage.maxDamagePerSecond())).toList();
        return new ZoneProfile(id, initialSizes, scaled, initialHalfSize, initialCenters);
    }
    /** Stable legacy spelling is part of the persisted rules hash; sorted centers make new hashes deterministic. */
    @Override public String toString() {
        String legacy = "ZoneProfile[id=" + id + ", initialSizes=" + initialSizes + ", stages=" + stages;
        return stageReferenceHalfSize == 0 && initialCenters.isEmpty() ? legacy + "]"
                : legacy + ", stageReferenceHalfSize=" + stageReferenceHalfSize + ", initialCenters=" + initialCenters + "]";
    }
    public double initialHalfSize(int actualPlayers) {
        if (actualPlayers < 1) throw new IllegalArgumentException("At least one starter required");
        return initialSizes.stream().filter(size -> actualPlayers <= size.maxPlayers()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Player count exceeds profile coverage")).halfSize();
    }
    public double largestReachableHalfSize(int roomMaximum) {
        initialHalfSize(roomMaximum);
        double largest = 0; int previous = 0;
        for (InitialSize size : initialSizes) {
            if (previous >= roomMaximum) break;
            largest = Math.max(largest, size.halfSize()); previous = size.maxPlayers();
        }
        return largest;
    }
    /** Inclusive player-count threshold. */
    public record InitialSize(int maxPlayers, double halfSize) {
        public InitialSize {
            Checks.finite(halfSize, "halfSize");
            if (maxPlayers < 1 || halfSize < 128) throw new IllegalArgumentException("maxPlayers >= 1 and halfSize >= 128 required");
        }
    }
    /** Damage applies during this stage's wait and shrink, and remains active in FINAL for the last stage. */
    public record Stage(Duration waitDuration, Duration shrinkDuration, double targetHalfSize,
                        double baseDamagePerSecond, double extraDamagePerBlock, double maxDamagePerSecond) {
        public Stage {
            Objects.requireNonNull(waitDuration); Objects.requireNonNull(shrinkDuration);
            if (waitDuration.isNegative() || shrinkDuration.isNegative() || shrinkDuration.isZero())
                throw new IllegalArgumentException("wait duration >= 0 and shrink duration > 0 required");
            Checks.finite(targetHalfSize, "targetHalfSize"); Checks.finite(baseDamagePerSecond, "baseDamagePerSecond");
            Checks.finite(extraDamagePerBlock, "extraDamagePerBlock"); Checks.finite(maxDamagePerSecond, "maxDamagePerSecond");
            if (targetHalfSize < 0 || baseDamagePerSecond < 0 || extraDamagePerBlock < 0 || maxDamagePerSecond < baseDamagePerSecond)
                throw new IllegalArgumentException("target size >= 0, damages >= 0 and max damage >= base damage required");
        }
    }
}
