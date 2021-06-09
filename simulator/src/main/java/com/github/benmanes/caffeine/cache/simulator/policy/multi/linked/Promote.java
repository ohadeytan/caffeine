/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.multi.linked;

import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;

import java.util.Set;
import java.util.stream.Stream;
import java.util.Random;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admission;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.MultilevelPolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * A cache that uses a linked list, in either insertion or access order, to implement simple
 * page replacement algorithms.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(name = "multi.Promote", characteristics = WEIGHTED)
public final class Promote implements Policy {
  final int levels;
  final Long2ObjectMap<Node> data;
  final MultilevelPolicyStats policyStats;
  final Admittor admittor;
  final long[] maximumSize;
  final Node[] sentinels;
  long[] currentSizes;
  long levelTwoWrites;
  final static boolean debug = false;
  final static boolean stats = true;
  private final Random random;
  
  public Promote(Config config, Set<Characteristic> characteristics,
      Admission admission) {
    BasicSettings settings = new BasicSettings(config);
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.multilevelMaximumSize().stream().mapToLong(l -> l).toArray();
    this.levels = maximumSize.length;
    this.policyStats = new MultilevelPolicyStats("multi.Promote", levels);
    this.admittor = admission.from(config, policyStats);
    this.sentinels = Stream.generate(() -> new Node()).limit(levels).toArray(Node[]::new);
    this.currentSizes = new long[levels];
    this.random = new Random(settings.randomSeed());
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config, Set<Characteristic> characteristics) {
    BasicSettings settings = new BasicSettings(config);
    return settings.admission().stream().map(admission ->
      new Promote(config, characteristics, admission)
    ).collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }
  
  @Override
  public void record(AccessEvent event) {
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);
    admittor.record(key);
    final Node headCandidate = new Node();
    if (old != null) { // Hit
      policyStats.recordWeightedHit(weight);
      for (int level = 0; level < old.level; level++) {
        policyStats.recordWeightedMiss(weight, level);           
      }
      policyStats.recordWeightedHit(weight, old.level);
      policyStats.recordOperation();
      evictEntry(old, old.level, headCandidate);
      old.level = -1;
    } else { // Miss
      policyStats.recordWeightedMiss(weight); 
      for (int level = 0; level < levels; level++) {
        policyStats.recordWeightedMiss(weight, level);   
      }
      Node node = new Node(key, weight, headCandidate, -1);
      node.appendToTail();
    } 
    for (int level = 0; level < levels; level++) {
      while (headCandidate.next.level < level) {
        Node candidate = headCandidate.next;
        candidate.level = level;
        if (candidate.weight > maximumSize[level]) {
          candidate.moveToTail();
          continue;
        }
        if (level == 1) {
          levelTwoWrites++;
        }
        data.put(candidate.key, candidate);
        currentSizes[level] += candidate.weight;
        candidate.sentinel = sentinels[level];
        candidate.moveToTail();
        while (currentSizes[level] > maximumSize[level]) {
          Node victim = sentinels[level].next;
          policyStats.recordOperation();
          policyStats.recordEviction();
    
          boolean admit = admittor.admit(candidate.key, victim.key);
          if (admit) {
            evictEntry(victim, level, headCandidate);
          } else {
            evictEntry(candidate, level, headCandidate);
          }
        }           
      }
    }
    printCache();
  }

  private boolean shouldPromoteUpwards() {
    return this.random.nextDouble() < 0.5;
  }
  
  private void printCache() {
    if (debug) {
      for (int level = 0; level < levels; level++) {
        System.out.print("level " + level + ": cache size = " + maximumSize[level] + ", items = [");
        Node node = sentinels[level].next;
        while (node != sentinels[level]) {
          System.out.print(node.key + ", ");
          node = node.next;
        }
        System.out.print("]\n");
      }
      System.out.print("------------------------------\n");
    }
  }
  
  @Override
  public void finished() {
    printCache();
    if (stats) {
      System.out.println("level_1_hits=" + policyStats.hitCount(0));
      System.out.println("level_1_misses=" + policyStats.missCount(0));
      System.out.println("level_2_hits=" + policyStats.hitCount(1));
      System.out.println("level_2_misses=" + policyStats.missCount(1));
      System.out.println("level_2_writes=" + levelTwoWrites);
      System.out.println("level_2_promotions=" + 0);
    }
  }

  private void evictEntry(Node node, int level, Node headCandidate) {
    currentSizes[level] -= node.weight;
    data.remove(node.key);
    node.sentinel = headCandidate;
    node.level = level;
    node.moveToTail();
  }

  /** A node on the double-linked list. */
  static final class Node {
    Node sentinel;

    boolean marked;
    Node prev;
    Node next;
    long key;
    int weight;
    int level;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.level = Integer.MAX_VALUE;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Node sentinel, int level) {
      this.sentinel = sentinel;
      this.key = key;
      this.weight = weight;
      this.level = level;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail() {
      Node tail = sentinel.prev;
      sentinel.prev = this;
      tail.next = this;
      next = sentinel;
      prev = tail;
    }

    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      prev = next = null;
      key = Long.MIN_VALUE;
    }

    /** Moves the node to the tail. */
    public void moveToTail() {
      // unlink
      prev.next = next;
      next.prev = prev;

      // link
      next = sentinel;
      prev = sentinel.prev;
      sentinel.prev = this;
      prev.next = this;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .add("marked", marked)
          .add("level", marked)
          .toString();
    }
  }
}
