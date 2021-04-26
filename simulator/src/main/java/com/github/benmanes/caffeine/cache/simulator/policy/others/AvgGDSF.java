package com.github.benmanes.caffeine.cache.simulator.policy.others;

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import akka.japi.Pair;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;

@PolicySpec(name = "others.AvgGDSF")
public class AvgGDSF extends GDSF {

  public AvgGDSF(Config config) {
    super(config);
    // policyStats.setName("others.AvgGDSF");
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new AvgGDSF(config));
  }

  
  @Override
  protected void evict(Node candidate) {
    ObjectBidirectionalIterator<Pair<Double, Long>> it = priorityQueue.iterator();
    long victimsSize = 0;
    double victimsPriority = 0;
    int victimsNum = 0;
    Pair<Double, Long> pair = null;
    while ((used + candidate.weight - victimsSize) > maximumSize) {
      pair = it.next();
      final double victimPriority = pair.first().doubleValue();
      final long victimKey = pair.second().longValue();
      victimsSize += data.get(victimKey).weight;
      victimsNum++;
      victimsPriority += victimPriority;
    }
    final double avgPriority = victimsPriority / victimsNum;
    if (avgPriority > candidate.priority) {
      policyStats.recordRejection();
      checkState(used <= maximumSize);
      return;
    } 

    clock = pair.first();
    while (used + candidate.weight > maximumSize) {
      evictVictim();
    }
    admit(candidate);
    policyStats.recordAdmission();
    checkState(used <= maximumSize);
  }
}
