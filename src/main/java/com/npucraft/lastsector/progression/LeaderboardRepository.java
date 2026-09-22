package com.npucraft.lastsector.progression;
import com.npucraft.lastsector.storage.StorageProvider;
import java.sql.*;
import java.util.*;
public final class LeaderboardRepository {
    public enum Scope { LIFETIME, DAY, WEEK, MONTH }
    public enum Metric { RATING, KILL_SCORE, WINS, KILLS, ASSISTS, DAMAGE }
    public record Query(Scope scope,String period,Metric metric,int page,int size) {
        public Query {
            Objects.requireNonNull(scope);Objects.requireNonNull(metric);Objects.requireNonNull(period);
            if(page<0 || size<1 || size>45 || page>Integer.MAX_VALUE/size)throw new IllegalArgumentException("Invalid leaderboard page");
        }
    }
    public record Row(UUID player,String name,double value) {}
    private final StorageProvider provider;
    public LeaderboardRepository(StorageProvider provider) {this.provider=provider;}
    public List<Row> query(Query query)throws SQLException {
        boolean lifetime=query.scope()==Scope.LIFETIME;
        String metric=switch(query.metric()) {
            case RATING -> lifetime?"rating":"rating_delta";
            case KILL_SCORE -> lifetime?"kill_score":"kill_score_delta";
            case WINS -> "wins"; case KILLS -> "kills"; case ASSISTS -> "assists"; case DAMAGE -> "damage";
        };
        String value=(lifetime?"p.":"s.")+metric;
        String from=lifetime?"player_profiles p":"player_period_stats s JOIN player_profiles p ON p.player_uuid=s.player_uuid";
        String where=lifetime?"":" WHERE s.period_type=? AND s.period_key=?";
        try(var c=provider.connect();var s=c.prepareStatement("SELECT p.player_uuid,p.last_known_name,"+value+" FROM "+from+where+" ORDER BY "+value+" DESC,p.last_known_name ASC,p.player_uuid ASC LIMIT ? OFFSET ?")) {
            int index=1;if(!lifetime){s.setString(index++,query.scope().name());s.setString(index++,query.period());}
            s.setInt(index++,query.size());s.setInt(index,query.page()*query.size());var rows=new ArrayList<Row>();
            try(var result=s.executeQuery()){while(result.next())rows.add(new Row(UUID.fromString(result.getString(1)),result.getString(2),result.getDouble(3)));}
            return List.copyOf(rows);
        }
    }
}
