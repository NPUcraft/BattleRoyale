package com.npucraft.lastsector.cosmetic;
import com.npucraft.lastsector.api.economy.EconomyProvider;
import java.util.*;
import java.util.concurrent.*;
/** All continuations and external economy calls run on the caller's main-thread dispatcher.
 * No generic external provider supports a transaction spanning our database.
 */
public final class PurchaseService {
    public interface AsyncDatabase { <T> CompletableFuture<T> call(Callable<T> work); }
    private final CosmeticRepository repository;
    private final AsyncDatabase database;
    private final Set<UUID> busy=new HashSet<>();
    private final Map<UUID,Purchase> review=new HashMap<>();
    private final Set<UUID> checking=new HashSet<>();
    public PurchaseService(CosmeticRepository repository,AsyncDatabase database){this.repository=repository;this.database=database;}
    public boolean busy(UUID player){return busy.contains(player)||review.values().stream().anyMatch(p->p.player().equals(player));}
    public void retryReviews() {
        for(var p:List.copyOf(review.values()))if(checking.add(p.id()))database.call(()->{
            var status=repository.status(p.id());
            if(status==Purchase.Status.PENDING || status==Purchase.Status.WITHDRAWING)repository.transition(p.id(),status,Purchase.Status.MANUAL_REVIEW,System.currentTimeMillis());
            return status;
        }).whenComplete((status,error)->{checking.remove(p.id());if(error==null)review.remove(p.id());});
    }
    public CompletableFuture<String> buy(UUID player,CosmeticDefinition definition,EconomyProvider economy,String provider,String currency) {
        if(busy(player)||!busy.add(player))return CompletableFuture.failedFuture(new IllegalStateException("Purchase already in progress"));
        CompletableFuture<String> result;
        try {
            result=database.call(()->repository.owns(player,definition.id())).thenCompose(owned->{
                if(owned)return CompletableFuture.completedFuture("Already owned");
                if(definition.price().signum()==0)return database.call(()->{repository.grant(player,definition.id(),System.currentTimeMillis());return "Unlocked";});
                if(economy==null || !economy.isAvailable())throw new IllegalStateException("Economy unavailable");
                if(!economy.has(player,definition.price()))throw new IllegalStateException("Insufficient balance");
                long now=System.currentTimeMillis();
                var purchase=new Purchase(UUID.randomUUID(),player,definition.id(),provider,currency,definition.price(),Purchase.Status.PENDING,now,now);
                return database.call(()->{repository.intent(purchase);repository.transition(purchase.id(),Purchase.Status.PENDING,Purchase.Status.WITHDRAWING,System.currentTimeMillis());return purchase;})
                    .thenCompose(p->withdraw(p,economy));
            });
        }catch(Throwable error){result=CompletableFuture.failedFuture(error);}
        return result.whenComplete((value,error)->busy.remove(player));
    }
    private CompletableFuture<String> withdraw(Purchase p,EconomyProvider economy) {
        final boolean withdrawn;
        try {withdrawn=economy.withdraw(p.player(),p.amount());}
        catch(Throwable uncertain){return mark(p,Purchase.Status.MANUAL_REVIEW,"Purchase uncertain; administrator review required: "+p.id());}
        if(!withdrawn)return mark(p,Purchase.Status.CANCELLED,"Withdrawal declined");
        return database.call(()->{repository.complete(p);return "Purchased";}).handle((value,error)-> {
            if(error==null)return CompletableFuture.completedFuture(value);
            // A lost commit acknowledgement can mean COMPLETED. Read durable state before compensation.
            return database.call(()->repository.status(p.id())).handle((status,readError)-> {
                if(readError!=null){review.put(p.id(),p);return CompletableFuture.completedFuture("Purchase unresolved; administrator review required: "+p.id());}
                if(status==Purchase.Status.COMPLETED)return CompletableFuture.completedFuture("Purchased");
                if(status!=Purchase.Status.WITHDRAWING)return CompletableFuture.completedFuture("Purchase requires administrator review: "+p.id());
                boolean refunded=false;try {refunded=economy.deposit(p.player(),p.amount());}catch(Throwable ignored) { /* external side effect is ambiguous */ }
                return mark(p,refunded?Purchase.Status.REFUNDED:Purchase.Status.MANUAL_REVIEW,refunded?"Purchase failed; refunded":"Refund unresolved; administrator review required: "+p.id());
            }).thenCompose(next->next);
        }).thenCompose(next->next);
    }
    private CompletableFuture<String> mark(Purchase p,Purchase.Status status,String message) {
        return database.call(()->{repository.transition(p.id(),Purchase.Status.WITHDRAWING,status,System.currentTimeMillis());return message;}).whenComplete((value,error)->{if(error!=null)review.put(p.id(),p);});
    }
}
