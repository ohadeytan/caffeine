package com.github.benmanes.caffeine.cache.simulator.policy;

public final class MultilevelPolicyStats extends PolicyStats {
  private final long hitCounts[];
  private final long missCounts[];

  public MultilevelPolicyStats(String name, int levels) {
    super(name);
    this.missCounts = new long[levels];
    this.hitCounts = new long[levels];
  }
  
  public void recordHit(int level) {
    hitCounts[level]++;
    hitCount++;
  }
  
  public void recordMiss(int level) {
    missCounts[level]++;
    missCount++;
  }

  public long hitCount(int level) {
    return hitCounts[level];
  }

  public long missCount(int level) {
    return missCounts[level];
  }

  public long requestCount(int level) {
    return hitCounts[level] + missCounts[level];
  }
}
