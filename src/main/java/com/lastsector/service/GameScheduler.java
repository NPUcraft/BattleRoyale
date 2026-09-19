package com.lastsector.service;
/** Replaceable server-thread clock; no domain object needs Bukkit scheduler access. */
public interface GameScheduler {
    Task repeat(int periodTicks, Runnable action);
    interface Task { void cancel(); }
}

