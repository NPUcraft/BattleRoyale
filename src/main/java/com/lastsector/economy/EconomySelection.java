package com.lastsector.economy;
import com.lastsector.api.economy.EconomyProvider;
import java.util.*;
import java.util.function.Function;
public record EconomySelection(String configured,String active,EconomyProvider provider,String currency,boolean shopEnabled) {
    public static EconomySelection select(String configured,List<String> priority,String currency,boolean enabled,Function<String,EconomyProvider> resolver) {
        for(String name:configured.equals("auto")?priority:List.of(configured)) {
            try{var provider=resolver.apply(name);if(provider!=null && provider.isAvailable())return new EconomySelection(configured,name,provider,currency,enabled);}
            catch(LinkageError|RuntimeException unavailable){ /* Optional plugin API/version failures cannot disable matches. */ }
        }
        return new EconomySelection(configured,"none",null,currency,false);
    }
    public String diagnostics(){return "configured="+configured+" active="+active+" currency="+currency+" available="+(provider!=null&&provider.isAvailable())+" shopEnabled="+shopEnabled;}
}
