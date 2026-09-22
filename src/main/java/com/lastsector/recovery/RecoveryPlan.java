package com.lastsector.recovery;
import com.lastsector.storage.RecoveryRepository;import java.util.*;
/** Pure conflict classification; all members of a conflicting ownership group are rejected. */
public final class RecoveryPlan {
 private RecoveryPlan(){}
 public static Set<UUID> duplicates(List<RecoveryRepository.Row> rows){var rooms=new HashMap<String,Integer>();var worlds=new HashMap<String,Integer>();rows.forEach(row->{rooms.merge(row.room(),1,Integer::sum);worlds.merge(row.world(),1,Integer::sum);});var result=new HashSet<UUID>();rows.stream().filter(row->rooms.get(row.room())>1||worlds.get(row.world())>1).forEach(row->result.add(row.session()));return Set.copyOf(result);}
 public static final class Rejected extends RuntimeException {public Rejected(String reason){super(reason);}}
}
