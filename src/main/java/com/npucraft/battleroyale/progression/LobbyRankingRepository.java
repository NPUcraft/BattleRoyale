package com.npucraft.battleroyale.progression;

import com.npucraft.battleroyale.storage.StorageProvider;
import java.sql.SQLException;
import java.util.*;

/** Called only on the existing bounded database worker, once per board refresh. */
public final class LobbyRankingRepository {
    private final StorageProvider provider;
    public LobbyRankingRepository(StorageProvider provider){this.provider=Objects.requireNonNull(provider);}
    public List<LobbyRankingRow> top(int limit)throws SQLException {
        if(limit<1||limit>8)throw new IllegalArgumentException("Lobby ranking size must be 1..8");
        try(var connection=provider.connect();var statement=connection.prepareStatement("SELECT player_uuid,last_known_name,rating,kills,wins FROM player_profiles ORDER BY rating DESC,last_known_name ASC,player_uuid ASC LIMIT ?")){
            statement.setInt(1,limit);var rows=new ArrayList<LobbyRankingRow>();
            try(var result=statement.executeQuery()){while(result.next())rows.add(new LobbyRankingRow(UUID.fromString(result.getString(1)),result.getString(2),result.getInt(3),result.getLong(4),result.getLong(5)));}
            return List.copyOf(rows);
        }
    }
}
