package com.lastsector.team;
import java.util.*;
/** Future party boundary; this milestone supplies singleton groups only. */
public record TeamAssignmentInput(List<List<UUID>> groups,int teamSize) {
    public TeamAssignmentInput {
        groups=groups.stream().map(List::copyOf).toList();
        if(teamSize<1 || groups.isEmpty() || groups.stream().anyMatch(g->g.size()!=1))
            throw new IllegalArgumentException("M6 automatic assignment requires singleton groups and positive capacity");
        if(groups.stream().flatMap(Collection::stream).distinct().count()!=groups.size())throw new IllegalArgumentException("Duplicate participant");
    }
    public static TeamAssignmentInput automatic(Collection<UUID> roster,int capacity) {
        return new TeamAssignmentInput(roster.stream().map(List::of).toList(),capacity);
    }
}
