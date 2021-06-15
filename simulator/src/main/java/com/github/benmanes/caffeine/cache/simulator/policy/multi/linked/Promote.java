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

import java.util.Arrays;

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
  
  double[] probPromote;
  double[] sizeRatio;
  double[] prevRatio;
  boolean[] adjust;
  long[] lastAdjust;
  private final double hintFreq = 0.05;
  long timestamp;

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
    this.probPromote = new double[levels];
    this.sizeRatio = new double[levels];
    this.prevRatio = new double[levels];
    this.adjust = new boolean[levels];
    this.lastAdjust = new long[levels];
    double sumSize = 0;
    for (int level = 0; level < levels; level++) {
      sizeRatio[level] = sumSize/(sumSize + maximumSize[level]);
      sumSize += maximumSize[level];
      probPromote[level] = sizeRatio[level];
    }
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
    timestamp++;
    adjustIfNeeded();
    final int weight = event.weight();
    final long key = event.key();
    Node old = data.get(key);
    admittor.record(key);
    int lowest_level = levels;
    boolean promoteHint = true;
    if (old == null) { // Miss
      policyStats.recordWeightedMiss(weight); 
      for (int level = 0; level < levels; level++) {
        policyStats.recordWeightedMiss(weight, level);   
      }
    } else { // Hit
      policyStats.recordWeightedHit(weight);
      for (int level = 0; level < old.level; level++) {
        policyStats.recordWeightedMiss(weight, level);           
      }
      policyStats.recordWeightedHit(weight, old.level);
      policyStats.recordOperation();
      lowest_level = old.level;
      promoteHint = shouldPromoteUpwards(old.level);
      if (promoteHint) {
        policyStats.recordEviction();
        evictEntry(old, old.level);
      } else {
        old.moveToTail();
        old.timestamp = timestamp;
      }
    } 
    for (int level = lowest_level-1; level >=0; level--) {
      if (promoteHint) {
        promoteHint = shouldPromoteUpwards(level);
        if (!promoteHint) {
          if (weight <= maximumSize[level]) {
            if (level == 1) {
              levelTwoWrites++;
            }
            Node node = new Node(key, weight, sentinels[level], level, timestamp);
            data.put(key, node);
            currentSizes[level] += weight;
            node.appendToTail();
            evictWhileNeeded(level, node);
          }
          break;
        }
      }
    }
    printCache();
  }

  private void adjustIfNeeded() {
    for (int level = 1; level < levels; level++) {
      long levelCacheLife = (long) (cacheLife(level)); 
      if (levelCacheLife > (maximumSize[level]/2) && (levelCacheLife*hintFreq) < (timestamp - lastAdjust[level])) {
        //System.out.println(level);
        //System.out.println(levelCacheLife);
        //System.out.println(timestamp - lastAdjust[level]);
        lastAdjust[level] = timestamp;
        adjust[level] = !adjust[level];
        if (adjust[level]) {
          double prev = prevRatio[level];
          double curr = (double) cacheLife(level - 1)/ (double) (levelCacheLife + cacheLife(level - 1));
          double f = (2*curr - 1);
          if ((f > 0 && ((prev-curr) < (hintFreq * (prev-0.5)))) || (f < 0 && ((curr-prev) < (hintFreq * (0.5-prev))))) {
           probPromote[level] += (1-probPromote[level])*probPromote[level]*f;
           if (probPromote[level] > sizeRatio[level]) {
             probPromote[level] = sizeRatio[level];
           }
          }
          //System.out.println(prev + " -> " + curr);
          //System.out.println(probPromote[level]);
          prevRatio[level] = curr;
        }
      }
    }
  }

  private long cacheLife(int level) {
    return sentinels[level].prev.timestamp - sentinels[level].next.timestamp;
  }

  private boolean shouldPromoteUpwards(int level) {
    if (level == 0 || probPromote[level] < this.random.nextDouble()) {
     return false;
    } else {
     return true;
    } 
  }

  private void evictWhileNeeded(int level, Node node) {
    while (currentSizes[level] > maximumSize[level]) {
      Node victim = sentinels[level].next;
      policyStats.recordOperation();
      policyStats.recordEviction();
      boolean admit = admittor.admit(node.key, victim.key);
      if (admit) {
        evictEntry(victim, level);
      } else {
        evictEntry(node, level);
      }
    }
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
      System.out.println("prob_promote=" + Arrays.toString(probPromote));
      System.out.println("adapt_ratio=" + Arrays.toString(prevRatio));
    }
  }

  private void evictEntry(Node node, int level) {
    currentSizes[level] -= node.weight;
    data.remove(node.key);
    node.remove();
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
    long timestamp;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.sentinel = this;
      this.prev = this;
      this.next = this;
      this.level = Integer.MAX_VALUE;
      this.timestamp = 0;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Node sentinel, int level, long timestamp) {
      this.sentinel = sentinel;
      this.key = key;
      this.weight = weight;
      this.level = level;
      this.timestamp = timestamp;
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
