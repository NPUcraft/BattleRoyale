package com.npucraft.battleroyale.zone;
/** Monotonic elapsed time; wall clock is only used for audit creation timestamps. */
@FunctionalInterface public interface GameClock {
    long nanoTime();
    static GameClock system() { return System::nanoTime; }
}

