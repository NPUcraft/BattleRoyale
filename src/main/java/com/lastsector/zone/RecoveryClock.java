package com.lastsector.zone;
/** Shared bootstrap pause: restoring another room cannot consume this room's gameplay deadlines. */
public final class RecoveryClock implements GameClock {
 private final GameClock source;private final long frozen;private long offset;private boolean paused=true;
 public RecoveryClock(GameClock source){this.source=source;frozen=source.nanoTime();}
 public long nanoTime(){return paused?frozen:source.nanoTime()-offset;}
 public void resume(){if(paused){offset=source.nanoTime()-frozen;paused=false;}}
}
