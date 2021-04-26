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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.sized;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.Random;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * An adaption of the TinyLfu policy that adds a temporal admission window. This window allows the
 * policy to have a high hit rate when entries exhibit a high temporal / low frequency pattern.
 * <p>
 * A new entry starts in the window and remains there as long as it has high temporal locality.
 * Eventually an entry will slip from the end of the window onto the front of the main queue. If the
 * main queue is already full, then a historic frequency filter determines whether to evict the
 * newly admitted entry or the victim entry chosen by main queue's policy. This process ensures that
 * the entries in the main queue have both a high recency and frequency. The window space uses LRU
 * and the main uses Segmented LRU.
 * <p>
 * Scan resistance is achieved by means of the window. Transient data will pass through from the
 * window and not be accepted into the main queue. Responsiveness is maintained by the main queue's
 * LRU and the TinyLfu's reset operation so that expired long term entries fade away.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@PolicySpec(characteristics = WEIGHTED)
public class SizedSampledTinyLfuPolicy implements Policy{
  private final Long2ObjectMap<Node> data;
  protected final PolicyStats policyStats;
  protected final Frequency sketch;
  protected final long maximumSize;
  
  private final Node headWindow;
  protected final LongArrayList main;

  protected final long maxWindow;
  protected final long maxMain;
  protected final boolean prune;
  protected final Sample sample;

  protected long sizeWindow;
  protected long sizeMain;
  protected long sizeData;
  protected long victimsCount;
  protected boolean scaled;
  

  public SizedSampledTinyLfuPolicy(double percentMain, SizedWindowTinyLfuSettings settings) {
    this.sketch = new PeriodicResetCountMin4(settings.config());
    //this.sketch = new PerfectFrequency(settings.config());
    String name = String.format("sketch.sized." + "SampledTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    this.policyStats = new PolicyStats(name);

    this.scaled = settings.scaled();
    this.sample = Sample.valueOf(settings.sample());
    this.prune = settings.prune();
    this.maxMain = (long) (settings.maximumSize() * percentMain);
    this.maxWindow = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.headWindow = new Node();
    this.main = new LongArrayList();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    SizedWindowTinyLfuSettings settings = new SizedWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new SizedSampledTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void record(AccessEvent event) {
    final long key = event.key();
    final int weight = event.weight();
    policyStats.recordOperation();
    Node node = data.get(key);
    if (node == null) {
      onMiss(key, weight);
      policyStats.recordWeightedMiss(weight);
    } else if (node.status == Status.WINDOW) {
      onWindowHit(node);
      policyStats.recordWeightedHit(weight);
    } else if (node.status == Status.MAIN) {
      onMainHit(node);
      policyStats.recordWeightedHit(weight);
    } else {
      throw new IllegalStateException();
    }
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key, int weight) {
    if (sizeData >= (maximumSize >>> 1)) {
      sketch.ensureCapacity(data.size());
    }
    sketch.increment(key);

    if (weight > (maxMain)) {
      policyStats.recordRejection();
      return;
    }
    Node node = new Node(key, weight, Status.WINDOW);
    if (weight > maxWindow) {
      node.appendNextToHead(headWindow);  
    } else {
      node.appendToTail(headWindow);
    }
    data.put(key, node);
    sizeWindow += weight;
    sizeData += weight;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node) {
    sketch.increment(node.key);
    node.moveToTail(headWindow);
  }

  private void onMainHit(Node node) {
    sketch.increment(node.key);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    final Node headCandidates = new Node();
    collectCandidates(headCandidates);
    while (headCandidates.prev != headCandidates) {
      Node candidate = headCandidates.prev;
      candidate.remove();
      if ((sizeMain + candidate.weight) > maxMain) {
        coreEviction(candidate);
      } else {
        admit(candidate);
      }
    }
  }
  
  protected void coreEviction(Node candidate) {
    Node victim = getVictim((sizeMain + candidate.weight) - maxMain);
    victimsCount++;
    if (compare(sketch.frequency(candidate.key), candidate.weight, sketch.frequency(victim.key), victim.weight)) {
      evictNode(victim);
      while ((sizeMain + candidate.weight) > maxMain) {
        Node evict = getVictim((sizeMain + candidate.weight) - maxMain);
        victimsCount++;
        evictNode(evict);
      }
      admit(candidate);
    } else {
      reject(candidate);
      main.add(victim.key);
    }    
  }
  
  protected void promote(Node node) {
  }

  protected boolean compare(int candidateFreq, int candidateWeight, int victimFreq, int victimWeight) {
    if (scaled) {
      return (candidateFreq * victimWeight) > (victimFreq * candidateWeight);
    }
    return candidateFreq >= victimFreq;
  }
  
  protected void admit(Node candidate) {
    main.add(candidate.key);
    sizeData += candidate.weight;
    sizeMain += candidate.weight;
    policyStats.recordAdmission();
  }

  protected void reject(Node candidate) {
    data.remove(candidate.key);
    policyStats.recordEviction();            
    policyStats.recordRejection();
  }
  
  protected void evictNode(Node evict) {
    data.remove(evict.key);
    sizeData -= evict.weight;
    if (evict.status == Status.MAIN) {
      sizeMain -= evict.weight;
    }
    policyStats.recordEviction();    
  }

  /**
   * Collect candidates  for eviction from the Window
   * @param headCandidates
   */
  private void collectCandidates(final Node headCandidates) {
    while (sizeWindow > maxWindow) {
      Node candidate = headWindow.next;
      candidate.status = Status.MAIN;
      sizeWindow -= candidate.weight;
      sizeData -= candidate.weight;
      candidate.remove();
      candidate.appendToTail(headCandidates);
    }
  }

  enum Sample {
    Rand, Freq, Size, FreqToSize, SizeDist
  }
  
  protected Node getVictim(long placeNeeded) {
    final int sample = 5;
    int victimIndex = Integer.MIN_VALUE;
    Node victimNode = null;
    switch (this.sample) {
    case Freq:
      int minFreq = Integer.MAX_VALUE;
      for (int i = 0; i < sample; i++) {
        int index = new Random().nextInt(main.size());
        Node node = data.get(main.getLong(index));
        int freq = sketch.frequency(node.key);
        if (freq < minFreq) {
          minFreq = freq;
          victimIndex = index;
          victimNode = node;
        }
      }
      break;
    case Size:
      int maxSize = Integer.MIN_VALUE;
      for (int i = 0; i < sample; i++) {
        int index = new Random().nextInt(main.size());
        Node node = data.get(main.getLong(index));
        int size = node.weight;
        if (size > maxSize) {
          maxSize = size;
          victimIndex = index;
          victimNode = node;
        }
      }
      break;
    case FreqToSize:
      double minScore = Double.MAX_VALUE;
      for (int i = 0; i < sample; i++) {
        int index = new Random().nextInt(main.size());
        Node node = data.get(main.getLong(index));
        int freq = sketch.frequency(node.key);
        double size = node.weight == 0 ? 0.001 : node.weight;
        double score = (double) freq / size;
        if (score < minScore) {
          minScore = score;
          victimIndex = index;
          victimNode = node;
        }
      }
      break;
    case SizeDist:
      int minDist = Integer.MAX_VALUE;
      //int neededSlab = 32 - Integer.numberOfLeadingZeros((int) (placeNeeded - 1));
      for (int i = 0; i < sample; i++) {
        int index = new Random().nextInt(main.size());
        Node node = data.get(main.getLong(index));
        //int currentSlab = 32 - Integer.numberOfLeadingZeros(node.weight - 1);
        //int dist = 2*Math.abs(neededSlab - currentSlab) - ((neededSlab - currentSlab) <= 0 ? 0 : 1);
        int dist = (int) Math.abs(placeNeeded - node.weight);
        if (dist < minDist) {
          minDist = dist;
          victimIndex = index;
          victimNode = node;
        }
      }
      break;
    case Rand:
    default:
      victimIndex = new Random().nextInt(main.size());
      victimNode = data.get(main.getLong(victimIndex));
      break;
    }
    main.set(victimIndex, main.getLong(main.size()-1));
    main.popLong();
    return victimNode;
  }


  @Override
  public void finished() {
    System.out.println("victims_count=" + victimsCount);
    long windowSize = data.values().stream().filter(n -> n.status == Status.WINDOW).mapToLong(node -> node.weight).sum();
    long mainSize = data.values().stream().filter(n -> n.status == Status.MAIN).mapToLong(node -> node.weight).sum();

    checkState(windowSize == sizeWindow);
    checkState(mainSize == (sizeData - windowSize));
    checkState(mainSize == sizeMain);
    checkState(sizeData <= maximumSize);
  }

  enum Status {
    WINDOW, MAIN
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;
    final int weight;

    Status status;
    Node prev;
    Node next;

    /** Creates a new sentinel node. */
    public Node() {
      this.key = Long.MIN_VALUE;
      this.weight = 0;
      this.prev = this;
      this.next = this;
    }

    /** Creates a new, unlinked node. */
    public Node(long key, int weight, Status status) {
      this.status = status;
      this.key = key;
      this.weight = weight;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
    }

    /** Appends the node to the next of the list head. */
    public void appendNextToHead(Node head) {
      Node nextHead = head.next;
      head.next = this;
      nextHead.prev = this;
      prev = head;
      next = nextHead;
    }
    
    /** Removes the node from the list. */
    public void remove() {
      prev.next = next;
      next.prev = prev;
      next = prev = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("weight", weight)
          .add("status", status)
          .toString();
    }
  }

  public static final class SizedWindowTinyLfuSettings extends WindowTinyLfuSettings {
    public SizedWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public boolean scaled() {
      return config().getBoolean("sized-window-tiny-lfu.scaled");
    }
    public boolean bump() {
      return config().getBoolean("sized-window-tiny-lfu.bump");
    }
    public boolean prune() {
      return config().getBoolean("sized-window-tiny-lfu.prune");
    }
    public String sample() {
      return config().getString("sized-window-tiny-lfu.sample").trim();
    }
  }
}
