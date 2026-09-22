package com.lastsector.progression;
import com.lastsector.session.GameSession;
import com.lastsector.cosmetic.SessionCosmeticLoadout;
import java.time.Instant;
import java.util.*;
/** Main-thread match statistics. Crash downtime never contributes to elapsed play time. */
public final class SessionProgress {
    public record Frozen(int rating,SessionCosmeticLoadout cosmetics) { public Frozen {if(rating<0)throw new IllegalArgumentException("Negative rating");Objects.requireNonNull(cosmetics);} }
    public record Snapshot(long startedAt,boolean eligible,PlacementTracker.Snapshot placements,
                           Map<UUID,Frozen> frozen,Map<UUID,Double> damage,Map<UUID,Long> deaths,Map<UUID,Long> eliminationTicks,
                           MatchResult result) {
        public Snapshot {frozen=Map.copyOf(frozen);damage=Map.copyOf(damage);deaths=Map.copyOf(deaths);eliminationTicks=Map.copyOf(eliminationTicks);}
    }
    private long startedAt;
    private final boolean eligible;
    private final PlacementTracker placements;
    private final Map<UUID,Frozen> frozen;
    private final Map<UUID,Double> damage=new HashMap<>();
    private final Map<UUID,Long> deaths=new HashMap<>();
    private final Map<UUID,Long> eliminationTicks=new HashMap<>();
    private MatchResult result;
    public SessionProgress(Set<UUID> teams,Map<UUID,Frozen> frozen) {
        this.startedAt=System.currentTimeMillis();eligible=true;placements=new PlacementTracker(teams);this.frozen=Map.copyOf(frozen);
    }
    public SessionProgress(Snapshot saved) {
        startedAt=saved.startedAt();eligible=saved.eligible();placements=new PlacementTracker(saved.placements());frozen=saved.frozen();damage.putAll(saved.damage());deaths.putAll(saved.deaths());eliminationTicks.putAll(saved.eliminationTicks());result=saved.result();
        if(!frozen.keySet().containsAll(damage.keySet()) || !frozen.keySet().containsAll(deaths.keySet()) || !deaths.keySet().equals(eliminationTicks.keySet()) || damage.values().stream().anyMatch(v->!Double.isFinite(v)||v<0) || deaths.values().stream().anyMatch(v->v<0))throw new IllegalArgumentException("Invalid recovered statistics");
    }
    public Frozen frozen(UUID player){return frozen.get(player);}
    public void started(){startedAt=System.currentTimeMillis();}
    public void damage(UUID attacker,double amount){if(frozen.containsKey(attacker) && Double.isFinite(amount) && amount>0)damage.merge(attacker,amount,Double::sum);}
    public void eliminated(UUID player,long elapsed,long tick){deaths.putIfAbsent(player,elapsed);eliminationTicks.putIfAbsent(player,tick);}
    public void observe(GameSession session,long tick) {
        if(result!=null)return;
        var active=session.teams().values().stream().filter(t->t.playerIds().stream().anyMatch(session::combatActive)).map(t->t.teamId()).collect(java.util.stream.Collectors.toSet());
        placements.observe(active,tick);
    }
    public MatchResult finish(GameSession session,long elapsed,RankingSettings settings,java.util.function.Function<UUID,String> names) {
        if(result!=null)return result;
        var outcome=session.outcome().orElseThrow();var assigned=placements.snapshot().assigned();
        var reason=eligible&&!outcome.winnerIds().isEmpty()?(outcome.tie()?MatchResult.CompletionReason.TIE:MatchResult.CompletionReason.NORMAL):MatchResult.CompletionReason.INTERNAL_ABORT;
        long completedAt=Math.max(startedAt,System.currentTimeMillis());
        var players=new ArrayList<MatchResult.PlayerResult>();
        for(var p:session.players().values()) {
            UUID team=p.teamId().orElseThrow();int placement=Objects.requireNonNull(assigned.get(team));var before=frozen.get(p.playerId());
            players.add(new MatchResult.PlayerResult(p.playerId(),names.apply(p.playerId()),team,placement,outcome.winnerIds().contains(p.playerId()),outcome.tie()&&outcome.winnerIds().contains(p.playerId()),p.kills(),deaths.containsKey(p.playerId())?1:0,p.assists(),damage.getOrDefault(p.playerId(),0.0),deaths.getOrDefault(p.playerId(),elapsed)/1_000_000_000L,before.rating(),0,before.rating(),0));
        }
        result=new MatchResult(session.sessionId(),session.room().id(),session.selectedMap().orElseThrow().id(),session.room().teamSize(),startedAt,completedAt,players.size(),session.teams().size(),reason,assigned,players,PeriodKeys.at(Instant.ofEpochMilli(completedAt),settings.zone()));return result;
    }
    public MatchResult abort(GameSession session,long elapsed,RankingSettings settings,java.util.function.Function<UUID,String> names,MatchResult.CompletionReason reason) {
        if(result!=null)return result;
        if(reason==MatchResult.CompletionReason.NORMAL || reason==MatchResult.CompletionReason.TIE)throw new IllegalArgumentException("Not an abort reason");
        var assigned=new HashMap<>(placements.snapshot().assigned());placements.snapshot().unresolved().forEach(team->assigned.put(team,1));
        long completedAt=Math.max(startedAt,System.currentTimeMillis());var players=new ArrayList<MatchResult.PlayerResult>();
        for(var p:session.players().values()) {
            UUID team=p.teamId().orElseThrow();var before=frozen.get(p.playerId());
            players.add(new MatchResult.PlayerResult(p.playerId(),names.apply(p.playerId()),team,assigned.get(team),false,false,p.kills(),deaths.containsKey(p.playerId())?1:0,p.assists(),damage.getOrDefault(p.playerId(),0.0),deaths.getOrDefault(p.playerId(),elapsed)/1_000_000_000L,before.rating(),0,before.rating(),0));
        }
        result=new MatchResult(session.sessionId(),session.room().id(),session.selectedMap().orElseThrow().id(),session.room().teamSize(),startedAt,completedAt,players.size(),session.teams().size(),reason,assigned,players,PeriodKeys.at(Instant.ofEpochMilli(completedAt),settings.zone()));return result;
    }
    public static MatchResult abandoned(com.lastsector.recovery.SessionRecoverySnapshot saved,long checkpointTime) {
        var progress=Objects.requireNonNull(saved.progression());if(progress.result()!=null)return progress.result();
        var placements=new HashMap<>(progress.placements().assigned());progress.placements().unresolved().forEach(id->placements.put(id,1));
        var players=new ArrayList<MatchResult.PlayerResult>();
        for(var p:saved.participants()) {
            var before=progress.frozen().get(p.id());
            players.add(new MatchResult.PlayerResult(p.id(),p.name(),p.team(),placements.get(p.team()),false,false,p.kills(),progress.deaths().containsKey(p.id())?1:0,p.assists(),progress.damage().getOrDefault(p.id(),0.0),progress.deaths().getOrDefault(p.id(),saved.elapsedNanos())/1_000_000_000L,before.rating(),0,before.rating(),0));
        }
        long completed=Math.max(progress.startedAt(),checkpointTime);
        return new MatchResult(saved.sessionId(),saved.roomId(),saved.mapId(),saved.teams().stream().mapToInt(t->t.members().size()).max().orElseThrow(),progress.startedAt(),completed,players.size(),saved.teams().size(),MatchResult.CompletionReason.RECOVERY_ABANDONED,placements,players,PeriodKeys.at(Instant.ofEpochMilli(completed),java.time.ZoneOffset.UTC));
    }
    public Snapshot snapshot(){return new Snapshot(startedAt,eligible,placements.snapshot(),frozen,damage,deaths,eliminationTicks,result);}
}
