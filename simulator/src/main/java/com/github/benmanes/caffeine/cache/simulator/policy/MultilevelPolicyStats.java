package com.github.benmanes.caffeine.cache.simulator.policy;

public final class MultilevelPolicyStats extends PolicyStats {
  private final long hitCounts[];
  private final long missCounts[];
  private final long hitsWeights[];
  private final long missesWeights[];


  public MultilevelPolicyStats(String name, int levels) {
    super(name);
    this.missCounts = new long[levels];
    this.hitCounts = new long[levels];
    this.hitsWeights = new long[levels];
    this.missesWeights = new long[levels];
  }
  
  public void recordHit(int level) {
    hitCounts[level]++;
  }
  
  public void recordWeightedHit(int weight, int level) {
    hitsWeights[level] += weight;
    recordHit(level);
  }  

  public void recordMiss(int level) {
    missCounts[level]++;
  }

  public void recordWeightedMiss(int weight, int level) {
    missesWeights[level] += weight;
    recordMiss(level);
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
