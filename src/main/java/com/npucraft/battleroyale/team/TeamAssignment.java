package com.npucraft.battleroyale.team;
import java.util.*;
import java.util.random.RandomGenerator;
import java.nio.charset.StandardCharsets;
public final class TeamAssignment {
    private TeamAssignment() {}
    public static List<GameTeam> assign(UUID session,TeamAssignmentInput input,RandomGenerator random) {
        List<UUID> roster=new ArrayList<>(input.groups().stream().map(List::getFirst).toList());
        for(int i=roster.size()-1;i>0;i--)Collections.swap(roster,i,random.nextInt(i+1));
        int count=(roster.size()-1)/input.teamSize()+1;
        List<Set<UUID>> members=new ArrayList<>();for(int i=0;i<count;i++)members.add(new LinkedHashSet<>());
        for(int i=0;i<roster.size();i++)members.get(i%count).add(roster.get(i));
        List<GameTeam> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(new GameTeam(
                UUID.nameUUIDFromBytes((session+":team:"+i).getBytes(StandardCharsets.UTF_8)),members.get(i),i+1));
        return List.copyOf(result);
    }
}
