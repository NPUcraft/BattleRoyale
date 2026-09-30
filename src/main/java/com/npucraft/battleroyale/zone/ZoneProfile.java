package com.npucraft.battleroyale.zone;
import com.npucraft.battleroyale.util.Checks;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
/** Immutable validated zone configuration and player-count bucket selection. */
public record ZoneProfile(String id, List<InitialSize> initialSizes, List<Stage> stages) {
    public ZoneProfile {
        Checks.text(id, "id");
        initialSizes = List.copyOf(initialSizes); stages = List.copyOf(stages);
        if (initialSizes.isEmpty() || stages.isEmpty()) throw new IllegalArgumentException("Zone lists must not be empty");
        int previous = 0;
        for (InitialSize size : initialSizes) {
            if (size.maxPlayers() <= previous) throw new IllegalArgumentException("max-players thresholds must strictly increase");
            previous = size.maxPlayers();
        }
        double previousSize = initialSizes.stream().mapToDouble(InitialSize::halfSize).min().orElseThrow();
        for (Stage stage : stages) {
            if (stage.targetHalfSize() >= previousSize) throw new IllegalArgumentException("stage target-half-size must strictly decrease");
            previousSize = stage.targetHalfSize();
        }
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
            if (maxPlayers < 1 || halfSize < 500) throw new IllegalArgumentException("maxPlayers >= 1 and halfSize >= 500 required");
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
            if (targetHalfSize <= 0 || baseDamagePerSecond < 0 || extraDamagePerBlock < 0 || maxDamagePerSecond < baseDamagePerSecond)
                throw new IllegalArgumentException("target size > 0, damages >= 0 and max damage >= base damage required");
        }
    }
}
