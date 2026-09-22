package com.lastsector.admin;

import java.util.*;

/** Fixed enum cardinality and a fixed 128-sample ring per timer; safe on IO and server threads. */
public final class PerformanceMetricsService {
  public static final PerformanceMetricsService LIVE = new PerformanceMetricsService();

  public enum Timer {
    ZONE_TICK,
    DB_WRITE,
    DB_OPERATION,
    CHECKPOINT,
    WORLD_CLONE,
    WORLD_CLEANUP,
    LOOT,
    RECOVERY
  }

  public enum Counter {
    PARTICLE_SAMPLES,
    CACHE_HIT,
    CACHE_MISS
  }

  public record Timing(long count, long lastNanos, long maxNanos, double rollingAverageNanos) {}

  private static final class Samples {
    long count, last, max, sum;
    int next, size;
    final long[] ring = new long[128];
  }

  private final EnumMap<Timer, Samples> timers = new EnumMap<>(Timer.class);
  private final EnumMap<Counter, Long> counters = new EnumMap<>(Counter.class);

  public PerformanceMetricsService() {
    for (var t : Timer.values()) timers.put(t, new Samples());
  }

  public synchronized void record(Timer timer, long nanos) {
    if (nanos < 0) throw new IllegalArgumentException("Negative duration");
    var s = timers.get(timer);
    s.count++;
    s.last = nanos;
    s.max = Math.max(s.max, nanos);
    s.sum -= s.ring[s.next];
    s.ring[s.next] = nanos;
    s.sum += nanos;
    s.next = (s.next + 1) % 128;
    s.size = Math.min(128, s.size + 1);
  }

  public synchronized void add(Counter counter, long value) {
    if (value < 0) throw new IllegalArgumentException("Negative count");
    counters.merge(counter, value, Long::sum);
  }

  public synchronized Map<Timer, Timing> timings() {
    var out = new EnumMap<Timer, Timing>(Timer.class);
    timers.forEach(
        (k, s) ->
            out.put(
                k, new Timing(s.count, s.last, s.max, s.size == 0 ? 0 : (double) s.sum / s.size)));
    return Map.copyOf(out);
  }

  public synchronized Map<Counter, Long> counters() {
    return Map.copyOf(counters);
  }
}
