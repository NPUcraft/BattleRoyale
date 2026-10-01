package com.npucraft.battleroyale.loot;

import java.util.ArrayList;
import java.util.List;

/** Tracks ground refill staging: the first observation only records the stage, later changes queue refills once. */
public final class GroundLootStages {
    private final GroundLootSettings settings;
    private int lastStage=-1;
    public GroundLootStages(GroundLootSettings settings){this.settings=settings;}
    public int lastStage(){return lastStage;}
    /**
     * The first observed stage is only recorded (recovery must not replay an already sealed plan).
     * A refill fires when the match enters the stage that follows its completed stage, i.e. after
     * stage {@code afterStage} has finished; repeats of the same stage are idempotent.
     */
    public List<GroundLootSettings.Refill> onStage(int stageNumber){
        if(lastStage<0){lastStage=stageNumber;return List.of();}
        if(stageNumber==lastStage)return List.of();
        lastStage=stageNumber;
        var due=new ArrayList<GroundLootSettings.Refill>();
        for(var refill:settings.refills())if(refill.afterStage()==stageNumber-1)due.add(refill);
        return List.copyOf(due);
    }
}
