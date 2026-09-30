package com.npucraft.battleroyale.session;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The original snapshot may leave match ownership only after the result is immutable and durable. */
public final class EndingReturnPolicy {
    private EndingReturnPolicy(){}
    public static void require(GameSession session,UUID player,CompletableFuture<?> resultDurable){
        if(session.state()!=GameState.ENDING || !session.players().containsKey(player))
            throw new IllegalStateException("比赛尚未结束，不能提前返回大厅。");
        if(session.outcome().isEmpty())throw new IllegalStateException("比赛结果尚未确定，请稍候再返回大厅。");
        if(resultDurable!=null && (!resultDurable.isDone() || resultDurable.isCompletedExceptionally() || resultDurable.isCancelled()))
            throw new IllegalStateException("比赛结果正在保存，请稍候再返回大厅。");
    }
}
