package com.npucraft.lastsector.session;
/** Lifecycle vocabulary. M2 transitions are guarded by GameSession; gameplay rules remain deferred. */
public enum GameState { WAITING, COUNTDOWN, PREPARING, STARTING, RUNNING, ENDING, CLEANUP }
