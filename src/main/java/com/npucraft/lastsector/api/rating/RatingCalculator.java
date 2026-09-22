package com.npucraft.lastsector.api.rating;
import com.npucraft.lastsector.team.GameTeam;
import java.time.Instant;
import java.util.*;
/** Pluggable rating strategy. No default formula is defined by the foundation. */
public interface RatingCalculator {
    /** Computes scores from immutable match facts without changing a live session or player. */
    Result calculate(Input input);
    /** Historical match facts, without holding a live session or Bukkit objects. */
    record Match(UUID sessionId, String roomId, Instant startedAt, Instant endedAt, int totalTeams) {
        public Match {
            Objects.requireNonNull(sessionId); Objects.requireNonNull(roomId);
            Objects.requireNonNull(startedAt); Objects.requireNonNull(endedAt);
            if (endedAt.isBefore(startedAt) || totalTeams < 1) throw new IllegalArgumentException("Invalid match facts");
        }
    }
    /** Placement is player-based, one-based and no greater than totalParticipants. */
    record Input(UUID playerId, int currentRating, int placement, int totalParticipants,
                 int kills, int assists, GameTeam team, Match match) {
        public Input {
            Objects.requireNonNull(playerId); Objects.requireNonNull(team); Objects.requireNonNull(match);
            if (placement < 1 || placement > totalParticipants || kills < 0 || assists < 0)
                throw new IllegalArgumentException("Invalid rating input");
            if (!team.playerIds().contains(playerId)) throw new IllegalArgumentException("Player must belong to team");
        }
    }
    /** Rating delta and a separate finite kill-performance score. */
    record Result(int ratingDelta, double killScore) {
        public Result {
            if (!Double.isFinite(killScore)) throw new IllegalArgumentException("killScore must be finite");
        }
    }
}
