package com.github.benmanes.caffeine.cache.simulator.policy.others;

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import akka.japi.Pair;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;

@PolicySpec(name = "others.CaffeineGDSF")
public class CaffeineGDSF extends GDSF {

  public CaffeineGDSF(Config config) {
    super(config);
    // policyStats.setName("others.CaffeineGDSF");
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new CaffeineGDSF(config));
  }

  
  @Override
  protected void evict(Node candidate) {   
    double victimPriority = 0;
    victimPriority = priorityQueue.first().first().doubleValue();
    if (victimPriority <= candidate.priority) {
      while ((used + candidate.weight) > maximumSize) {
        victimPriority = evictVictim();
      }
    } 

    if ((used + candidate.weight) > maximumSize) {
      policyStats.recordRejection();
      checkState(used <= maximumSize);
      return;
    } 

    clock = victimPriority;
    admit(candidate);
    policyStats.recordAdmission();
    checkState(used <= maximumSize);
  }
}
