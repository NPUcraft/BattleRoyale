package com.npucraft.battleroyale.progression;
import com.npucraft.battleroyale.storage.StorageProvider;
import com.google.gson.Gson;
import java.sql.*;
import java.util.*;
/** Worker-only repository. The session primary key is the sole official-result idempotency boundary. */
public final class PermanentRepository {
    private final StorageProvider provider;
    private final RankingSettings settings;
    private final Gson gson=new Gson();
    public PermanentRepository(StorageProvider provider, RankingSettings settings) { this.provider=provider; this.settings=settings; }
    private void ensure(Connection c, UUID id,String name,long now)throws SQLException {
        String insert=provider.type().equals("mysql")?"INSERT IGNORE":"INSERT OR IGNORE";
        try(var s=c.prepareStatement(insert+" INTO player_profiles(player_uuid,last_known_name,first_seen,last_seen,rating,highest_rating) VALUES(?,?,?,?,?,?)")) {
            s.setString(1,id.toString()); s.setString(2,name); s.setLong(3,now); s.setLong(4,now); s.setInt(5,settings.initial());s.setInt(6,settings.initial());s.executeUpdate();
        }
    }
    public PlayerProfile load(UUID id,String name,long now)throws SQLException {
        try(var c=provider.connect()) {
            c.setAutoCommit(false);
            try {
                ensure(c,id,name,now);
                try(var s=c.prepareStatement("UPDATE player_profiles SET last_known_name=?,last_seen=? WHERE player_uuid=?")) {
                    s.setString(1,name);s.setLong(2,now);s.setString(3,id.toString());s.executeUpdate();
                }
                var result=profile(c,id); c.commit();return result;
            } catch(SQLException|RuntimeException e) {c.rollback();throw e;}
        }
    }
    private PlayerProfile profile(Connection c,UUID id)throws SQLException {
        Set<String> unlocks=new HashSet<>();Map<String,String> equipped=new HashMap<>();
        try(var s=c.prepareStatement("SELECT cosmetic_id FROM cosmetic_unlocks WHERE player_uuid=?")) {
            s.setString(1,id.toString());try(var r=s.executeQuery()){while(r.next())unlocks.add(r.getString(1));}
        }
        try(var s=c.prepareStatement("SELECT category,cosmetic_id FROM cosmetic_equipped WHERE player_uuid=?")) {
            s.setString(1,id.toString());try(var r=s.executeQuery()){while(r.next())equipped.put(r.getString(1),r.getString(2));}
        }
        try(var s=c.prepareStatement("SELECT * FROM player_profiles WHERE player_uuid=?")) {
            s.setString(1,id.toString());try(var r=s.executeQuery()) {
                if(!r.next())throw new SQLException("Profile missing");
                return new PlayerProfile(id,r.getString("last_known_name"),r.getLong("first_seen"),r.getLong("last_seen"),r.getInt("rating"),r.getInt("highest_rating"),r.getLong("kill_score"),r.getLong("matches"),r.getLong("wins"),r.getLong("kills"),r.getLong("deaths"),r.getLong("assists"),r.getDouble("damage"),r.getLong("play_seconds"),r.getLong("top3"),r.getInt("best_placement"),unlocks,equipped);
            }
        }
    }
    public Optional<MatchResult> result(UUID session)throws SQLException {
        try(var c=provider.connect()) {return result(c,session);}
    }
    private Optional<MatchResult> result(Connection c,UUID session)throws SQLException {
        try(var s=c.prepareStatement("SELECT payload FROM match_results WHERE session_id=?")) {
            s.setString(1,session.toString());try(var r=s.executeQuery()){return r.next()?Optional.of(gson.fromJson(r.getString(1),MatchResult.class)):Optional.empty();}
        }
    }
    public MatchResult finalizeResult(MatchResult facts)throws SQLException {long start=System.nanoTime();try{return finalizeMeasured(facts);}finally{com.npucraft.battleroyale.admin.PerformanceMetricsService.LIVE.record(com.npucraft.battleroyale.admin.PerformanceMetricsService.Timer.DB_WRITE,System.nanoTime()-start);}}
    private MatchResult finalizeMeasured(MatchResult facts)throws SQLException {
        try(var c=provider.connect()) {
            c.setAutoCommit(false);
            try {
                var existing=result(c,facts.sessionId());if(existing.isPresent()){c.rollback();return existing.get();}
                // Reserve id before any player mutation; any conflict rolls back the complete transaction.
                try(var s=c.prepareStatement("INSERT INTO match_results(session_id,completed_at,completion_reason,payload) VALUES(?,?,?,?)")) {
                    s.setString(1,facts.sessionId().toString());s.setLong(2,facts.completedAt());s.setString(3,facts.reason().name());s.setString(4,"pending");s.executeUpdate();
                }
                var players=new ArrayList<MatchResult.PlayerResult>();
                for(var p:facts.players()) {
                    ensure(c,p.playerId(),p.name(),facts.completedAt());
                    if(provider.type().equals("mysql")) try(var s=c.prepareStatement("SELECT rating FROM player_profiles WHERE player_uuid=? FOR UPDATE")) {
                        s.setString(1,p.playerId().toString());try(var ignored=s.executeQuery()){while(ignored.next()) { /* acquire row lock */ }}
                    }
                    var profile=profile(c,p.playerId());
                    // Apply the placement award to the transactionally current rating; preserve frozen ratingBefore as match history.
                    var calculator=new PlacementRatingCalculator(settings);
                    int delta=facts.official()?calculator.delta(profile.rating(),p.placement(),facts.totalTeams()):0;
                    int after=Math.addExact(profile.rating(),delta);
                    long score=facts.official()?calculator.killScore(p.kills(),p.assists()):0;
                    var saved=new MatchResult.PlayerResult(p.playerId(),p.name(),p.teamId(),p.placement(),p.winner(),p.tieWinner(),p.kills(),p.deaths(),p.assists(),p.damage(),p.survivalSeconds(),p.ratingBefore(),delta,after,score);
                    players.add(saved);
                    try(var s=c.prepareStatement("INSERT INTO player_match_results(session_id,player_uuid,payload) VALUES(?,?,?)")) {
                        s.setString(1,facts.sessionId().toString());s.setString(2,p.playerId().toString());s.setString(3,gson.toJson(saved));s.executeUpdate();
                    }
                    if(facts.official()) apply(c,saved,facts.periods(),Math.max(profile.highestRating(),after));
                }
                var saved=new MatchResult(facts.sessionId(),facts.roomId(),facts.mapId(),facts.teamSize(),facts.startedAt(),facts.completedAt(),facts.totalPlayers(),facts.totalTeams(),facts.reason(),facts.placements(),players,facts.periods());
                try(var s=c.prepareStatement("UPDATE match_results SET payload=? WHERE session_id=?")) {s.setString(1,gson.toJson(saved));s.setString(2,facts.sessionId().toString());s.executeUpdate();}
                c.commit();return saved;
            } catch(SQLException failure) {c.rollback();var committed=result(c,facts.sessionId());if(committed.isPresent())return committed.get();throw failure;}
            catch(RuntimeException failure){c.rollback();throw failure;}
        }
    }
    private void apply(Connection c,MatchResult.PlayerResult p,PeriodKeys periods,int highest)throws SQLException {
        try(var s=c.prepareStatement("UPDATE player_profiles SET rating=?,highest_rating=?,kill_score=kill_score+?,matches=matches+1,wins=wins+?,kills=kills+?,deaths=deaths+?,assists=assists+?,damage=damage+?,play_seconds=play_seconds+?,top3=top3+?,best_placement=CASE WHEN best_placement=0 OR best_placement>? THEN ? ELSE best_placement END WHERE player_uuid=?")) {
            s.setInt(1,p.ratingAfter());s.setInt(2,highest);s.setLong(3,p.killScoreDelta());s.setInt(4,p.winner()?1:0);s.setInt(5,p.kills());s.setInt(6,p.deaths());s.setInt(7,p.assists());s.setDouble(8,p.damage());s.setLong(9,p.survivalSeconds());s.setInt(10,p.placement()<=3?1:0);s.setInt(11,p.placement());s.setInt(12,p.placement());s.setString(13,p.playerId().toString());s.executeUpdate();
        }
        for(var period:periods.entries().entrySet()) {
            String insert=provider.type().equals("mysql")?"INSERT IGNORE":"INSERT OR IGNORE";
            try(var s=c.prepareStatement(insert+" INTO player_period_stats(period_type,period_key,player_uuid) VALUES(?,?,?)")) {
                s.setString(1,period.getKey());s.setString(2,period.getValue());s.setString(3,p.playerId().toString());s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE player_period_stats SET matches=matches+1,wins=wins+?,kills=kills+?,deaths=deaths+?,assists=assists+?,damage=damage+?,rating_delta=rating_delta+?,kill_score_delta=kill_score_delta+? WHERE period_type=? AND period_key=? AND player_uuid=?")) {
                s.setInt(1,p.winner()?1:0);s.setInt(2,p.kills());s.setInt(3,p.deaths());s.setInt(4,p.assists());s.setDouble(5,p.damage());s.setInt(6,p.ratingDelta());s.setLong(7,p.killScoreDelta());s.setString(8,period.getKey());s.setString(9,period.getValue());s.setString(10,p.playerId().toString());s.executeUpdate();
            }
        }
    }
}
