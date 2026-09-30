package com.npucraft.battleroyale.storage;
import java.nio.file.*;
public record StorageSettings(String type,Path sqliteFile,String host,int port,String database,String username,String password,int timeoutMillis) {
    public static StorageSettings defaults(String type,Path data){return new StorageSettings(type,data.resolve("data/battleroyale.db"),"127.0.0.1",3306,"battleroyale","battleroyale","",5000);}
    public StorageSettings {
        if(!java.util.Set.of("sqlite","mysql").contains(type) || port<1 || port>65535 || timeoutMillis<100 || timeoutMillis>60000 || !host.matches("[a-zA-Z0-9.:-]+") || !database.matches("[a-zA-Z0-9_]+"))throw new IllegalArgumentException("Invalid storage settings");
    }
    @Override public String toString(){return "StorageSettings[provider="+type+", credentials=redacted]";}
}
