/*
 * Copyright 2020 Ohad Eytan. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator.policy.others;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;

import java.util.Comparator;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import akka.japi.Pair;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectSortedSet;

/**
 * Greedy Dual Size Frequency (GDSF) algorithm.
 * <p>
 * The algorithm is explained by the authors in
 * <a href="https://www.researchgate.net/profile/Ludmila_Cherkasova/publication/228542715_Improving_WWW_proxies_performance_with_Greedy-Dual-Size-Frequency_caching_policy/links/00b7d52113eb7014ad000000/Improving-WWW-proxies-performance-with-Greedy-Dual-Size-Frequency-caching-policy.pdf">
 * Improving-WWW-proxies-performance-with-Greedy-Dual-Size-Frequency-caching-policy</a>
 * 
 * @author ohadey@gmail.com (Ohad Eytan)
 */
public class GDSF implements Policy {
  private final PolicyStats policyStats;
  private final long maximumSize;

  final Long2ObjectMap<Node> data;
  final ObjectSortedSet<Pair<Double, Long>> priorityQueue;
  
  private double clock;
  private long used;
  
  public GDSF(Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.policyStats = new PolicyStats("others.GDSF");
    this.maximumSize = settings.maximumSizeLong();
    this.data = new Long2ObjectOpenHashMap<>();
    this.priorityQueue = new ObjectAVLTreeSet<Pair<Double,Long>>(new Comparator<Pair<Double,Long>>() {
      @Override
      public int compare(Pair<Double,Long> o1, Pair<Double,Long> o2) {
        return (o1.first().compareTo(o2.first()) == 0) 
            ? o1.second().compareTo(o2.second()) 
            : o1.first().compareTo(o2.first());
      }
    });
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new GDSF(config));
  }

  @Override
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    policyStats.recordOperation();
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);
    if (old == null) {
      policyStats.recordWeightedMiss(weight);
      onMiss(key, weight);
    } else {
      policyStats.recordWeightedHit(weight);
      onHit(old);
    }
  }

  private void onHit(Node old) {
    promote(old);
  }

  /**
   * Update priority
   */
  protected void promote(Node old) {
    old.frequency += 1;
    priorityQueue.remove(new Pair<Double, Long>(old.priority, old.key));
    old.priority = priority(old.frequency, old.weight);
    priorityQueue.add(new Pair<Double, Long>(old.priority, old.key));
  }

  private void onMiss(final long key, final int weight) {
    if (weight > maximumSize) {
      policyStats.recordRejection();
      return;
    }
    Node node = new Node(key, weight);
    double p = priority(1, weight);
    node.priority = p;
    
    if (used + weight <= maximumSize) {
      priorityQueue.add(new Pair<Double, Long>(p, key));
      data.put(key, node);
      used += weight;
      policyStats.recordAdmission();
      return;
    }
    
    evict(node);
  }

  /**
   */
  protected void evict(Node candidate) {
    ObjectBidirectionalIterator<Pair<Double, Long>> it = priorityQueue.iterator();
    long victimsSize = 0;
    Pair<Double, Long> pair = null;
    while ((used + candidate.weight - victimsSize) > maximumSize) {
      pair = it.next();
      final double victimPriority = pair.first().doubleValue();
      if (victimPriority > candidate.key) {
        break;
      }
      final long victimKey = pair.second().longValue();
      victimsSize += data.get(victimKey).weight;
    }

    if (pair.first().doubleValue() > candidate.priority) {
      policyStats.recordRejection();
      checkState(used <= maximumSize);
      return;
    } 

    clock = pair.first();
    admit(candidate);
    while (used > maximumSize) {
      evictVictim();
    }
    policyStats.recordAdmission();
    checkState(used <= maximumSize);
  }

  /**
   * @param candidate
   * @param pair
   */
  protected void admit(Node candidate) {
    priorityQueue.add(new Pair<Double, Long>(candidate.priority, candidate.key));
    data.put(candidate.key, candidate);
    used += candidate.weight;
  }

  /**
   * Evicts the item with the minimal priority
   */
  protected void evictVictim() {
    ObjectBidirectionalIterator<Pair<Double, Long>> it;
    it = priorityQueue.iterator();
    long victim_key = it.next().second().longValue();
    used -= data.get(victim_key).weight;
    data.remove(victim_key);
    it.remove();
    policyStats.recordEviction();
  }
  
  @Override
  public void finished() {
    checkState(used <= maximumSize);
    long actual_used = data.values().stream().mapToLong(node -> node.weight).sum();
    checkState(actual_used == used);
    actual_used = priorityQueue.stream().mapToLong(node -> data.get(node.second().longValue()).weight).sum();
    checkState(actual_used == used);
  }

  private double priority(int frequency, int weight) {
    return clock + (double)frequency/weight;
  }
  
  static final class Node {
    final long key;
    final int weight;
    int frequency;
    double priority;
    
    public Node(long key, int weight) {
      this.key = key;
      this.weight = weight;
      this.frequency = 1;
    }
  }
}
