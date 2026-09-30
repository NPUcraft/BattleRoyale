package com.npucraft.battleroyale.progression;

import com.npucraft.battleroyale.storage.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LobbyRankingRepositoryTest {
    @TempDir Path root;
    @Test void persistedProfilesReturnFaithfulLifetimeDataAndStableRatingOrder()throws Exception{
        var provider=new JdbcStorageProvider(StorageSettings.defaults("sqlite",root));new JdbcRecoveryRepository(provider).migrate();
        var repository=new LobbyRankingRepository(provider);assertTrue(repository.top(8).isEmpty());
        var permanent=new PermanentRepository(provider,RankingSettings.defaults());
        UUID low=UUID.randomUUID(),tieB=UUID.randomUUID(),tieA=UUID.randomUUID();
        permanent.load(low,"Lower",1);permanent.load(tieB,"Bravo",1);permanent.load(tieA,"Alpha",1);
        try(var connection=provider.connect();var update=connection.prepareStatement("UPDATE player_profiles SET rating=?,kills=?,wins=? WHERE player_uuid=?")){
            for(var row:List.of(new LobbyRankingRow(low,"Lower",900,8,2),new LobbyRankingRow(tieB,"Bravo",1200,20,4),new LobbyRankingRow(tieA,"Alpha",1200,30,6))){
                update.setInt(1,row.rating());update.setLong(2,row.kills());update.setLong(3,row.wins());update.setString(4,row.player().toString());update.executeUpdate();
            }
        }
        var rows=repository.top(2);assertEquals(List.of(tieA,tieB),rows.stream().map(LobbyRankingRow::player).toList());
        assertEquals(new LobbyRankingRow(tieA,"Alpha",1200,30,6),rows.getFirst());assertThrows(UnsupportedOperationException.class,()->rows.clear());
        assertEquals(3,repository.top(8).size());
    }
    @Test void displayLimitIsBoundedBeforeOpeningStorage(){
        var provider=new StorageProvider(){public java.sql.Connection connect(){throw new AssertionError("Must not connect");}public String type(){return "test";}};
        var repository=new LobbyRankingRepository(provider);assertThrows(IllegalArgumentException.class,()->repository.top(0));assertThrows(IllegalArgumentException.class,()->repository.top(9));
    }
}
