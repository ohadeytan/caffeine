package com.github.benmanes.caffeine.cache.simulator.policy.others;

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import akka.japi.Pair;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;

public class RistrettoGDSF extends GDSF {

  public RistrettoGDSF(Config config) {
    super(config);
    policyStats.setName("others.RistrettoGDSF");
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new RistrettoGDSF(config));
  }

  
  @Override
  protected void evict(Node candidate) {   
    double victimPriority = 0;
    while ((used + candidate.weight) > maximumSize) {
      victimPriority = priorityQueue.first().first().doubleValue();
      if (victimPriority <= candidate.priority) {
        evictVictim();
      } else {
        break;
      }
    }
    if ((used + candidate.weight) > maximumSize) {
      policyStats.recordRejection();
      checkState(used <= maximumSize);
      return;
    } 

    clock = victimPriority;
    while (used + candidate.weight > maximumSize) {
      evictVictim();
    }
    admit(candidate);
    policyStats.recordAdmission();
    checkState(used <= maximumSize);
  }
}
