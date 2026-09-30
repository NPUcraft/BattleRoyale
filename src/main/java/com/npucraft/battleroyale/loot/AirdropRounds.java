package com.npucraft.battleroyale.loot;

import com.npucraft.battleroyale.zone.ZonePhase;
import java.util.Objects;
import java.util.OptionalInt;

/** One claim per shrinking stage; warning selection may begin during its last 60 waiting seconds. */
public final class AirdropRounds {
    private int issued=-1;
    public AirdropRounds() {}
    /** Recovery skips elapsed shrink phases; the disk ledger also suppresses already-announced WAITING rounds. */
    public AirdropRounds(int stage,ZonePhase phase){issued=due(stage,phase,Double.MAX_VALUE);}
    private static int due(int stage,ZonePhase phase,double remainingSeconds){
        if(stage<0)throw new IllegalArgumentException("Negative stage");Objects.requireNonNull(phase);
        if(!Double.isFinite(remainingSeconds)||remainingSeconds<0)throw new IllegalArgumentException("Invalid remaining seconds");
        return phase==ZonePhase.WAITING&&remainingSeconds>AirdropSettings.ANNOUNCEMENT_SECONDS?stage-1:stage;
    }
    /** Legacy phase-only callers retain shrink-start semantics. */
    public OptionalInt poll(int stage,ZonePhase phase){return poll(stage,phase,Double.MAX_VALUE);}
    public OptionalInt poll(int stage,ZonePhase phase,double remainingSeconds){
        return issued<due(stage,phase,remainingSeconds)?OptionalInt.of(++issued):OptionalInt.empty();
    }
}
