package com.npucraft.battleroyale.api.party;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
/** External party boundary. Empty means no party; returned membership includes the queried player. */
public interface PartyProvider {
    /** Returns an immutable membership snapshot including playerId, or empty when there is no party. */
    Optional<Set<UUID>> findMembers(UUID playerId);
    /** Determines whether the player currently belongs to a party. */
    default boolean hasParty(UUID playerId) { return findMembers(playerId).isPresent(); }
}
