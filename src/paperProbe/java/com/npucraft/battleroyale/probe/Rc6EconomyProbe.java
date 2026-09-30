package com.npucraft.battleroyale.probe;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import su.nightexpress.coinsengine.api.CoinsEngineAPI;

/** Opt-in disposable-server balance persistence test; never shipped with BattleRoyale. */
public final class Rc6EconomyProbe {
    private static final UUID USER=UUID.fromString("132f779f-1dc3-4d77-aafa-7fe5965a19e6");
    private final JavaPlugin plugin;
    public Rc6EconomyProbe(JavaPlugin plugin){this.plugin=plugin;}
    public void command(CommandSender sender,String[] args){
        if(!Boolean.getBoolean("battleroyale.probe.rc6")||!plugin.getServer().getOnlinePlayers().isEmpty())throw new IllegalStateException("Isolated rc6 probe only");
        if(args.length!=2||!Set.of("seed","verify").contains(args[1]))throw new IllegalArgumentException("p26rc6economy seed|verify");
        if(!CoinsEngineAPI.isLoaded()||!CoinsEngineAPI.hasCurrency("coins"))throw new IllegalStateException("CoinsEngine coins unavailable");
        boolean seed=args[1].equals("seed");
        CoinsEngineAPI.getUserDataAsync(USER).whenComplete((saved,error)->main(()->{
            if(error!=null){fail(sender,error);return;}
            try{
                if(!seed){
                    if(saved.isEmpty()||CoinsEngineAPI.getBalance(USER,"coins")!=136)throw new IllegalStateException("Persisted balance did not survive restart");
                    sender.sendMessage("RC6 ECONOMY VERIFY SUCCESS balance=136");return;
                }
                if(saved.isPresent())throw new IllegalStateException("Probe account already exists; do not seed twice");
                var manager=CoinsEngineAPI.getUserManager();var user=manager.create(USER,"RC6BalanceProbe");
                CompletableFuture.runAsync(()->manager.getDataAccessor().insert(user)).whenComplete((ignored,insertError)->main(()->{
                    if(insertError!=null){fail(sender,insertError);return;}
                    try{
                        manager.getRepository().addPermanent(user);
                        if(!CoinsEngineAPI.setBalance(USER,"coins",123)||!CoinsEngineAPI.addBalance(USER,"coins",20)||!CoinsEngineAPI.removeBalance(USER,"coins",7)
                                ||CoinsEngineAPI.getBalance(USER,"coins")!=136)throw new IllegalStateException("Balance arithmetic failed");
                        CompletableFuture.runAsync(()->manager.getDataAccessor().update(user)).whenComplete((v,e)->main(()->{
                            if(e!=null)fail(sender,e);else sender.sendMessage("RC6 ECONOMY SEED SUCCESS balance=136");
                        }));
                    }catch(Throwable failure){fail(sender,failure);}
                }));
            }catch(Throwable failure){fail(sender,failure);}
        }));
    }
    private void main(Runnable work){plugin.getServer().getScheduler().runTask(plugin,work);}
    private void fail(CommandSender sender,Throwable failure){plugin.getLogger().log(java.util.logging.Level.SEVERE,"RC6 ECONOMY FAILED",failure);sender.sendMessage("RC6 ECONOMY FAILED "+failure);}
}
