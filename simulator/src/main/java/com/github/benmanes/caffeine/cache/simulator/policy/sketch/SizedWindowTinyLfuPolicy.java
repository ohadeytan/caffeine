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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;

import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.SizedWindowTinyLfuPolicy.Node;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Admittor;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.admission.TinyLfu;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

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
public final class SizedWindowTinyLfuPolicy implements Policy{
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final Frequency sketch;
  private final int maximumSize;
  private final Version version;
  
  private final Node headWindow;
  private final Node headProbation;
  private final Node headProtected;

  private final int maxWindow;
  private final int maxProtected;

  private int sizeWindow;
  private int sizeProtected;
  private int sizeData;
  

  public SizedWindowTinyLfuPolicy(double percentMain, SizedWindowTinyLfuSettings settings) {
    this.version = settings.version();
    String name = String.format("sketch." + StringUtils.capitalize(version.name().toLowerCase()) + 
        "SizedWindowTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    this.policyStats = new PolicyStats(name);
    this.sketch = new PeriodicResetCountMin4(settings.config());
    
    int maxMain = (int) (settings.maximumSize() * percentMain);
    this.maxProtected = (int) (maxMain * settings.percentMainProtected());
    this.maxWindow = settings.maximumSize() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSize();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    SizedWindowTinyLfuSettings settings = new SizedWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new SizedWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
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
    } else if (node.status == Status.PROBATION) {
      onProbationHit(node);
      policyStats.recordWeightedHit(weight);
    } else if (node.status == Status.PROTECTED) {
      onProtectedHit(node);
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

    if (weight > (maximumSize - maxWindow)) {
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

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    sketch.increment(node.key);

    node.remove();
    node.status = Status.PROTECTED;
    node.appendToTail(headProtected);

    sizeProtected += node.weight;
    while (sizeProtected > maxProtected) {
      Node demote = headProtected.next;
      demote.remove();
      demote.status = Status.PROBATION;
      demote.appendToTail(headProbation);
      sizeProtected -= demote.weight;
    }
  }

  /** Moves the entry to the MRU position, if it falls outside of the fast-path threshold. */
  private void onProtectedHit(Node node) {
    sketch.increment(node.key);
    node.moveToTail(headProtected);
  }

  /**
   * Evicts from the admission window into the probation space. If the size exceeds the maximum,
   * then the admission candidate and probation's victim are evaluated and one is evicted.
   */
  private void evict() {
    final Node headCandidates = new Node();
    while (sizeWindow > maxWindow) {
      Node candidate = headWindow.next;
      sizeWindow -= candidate.weight;
      sizeData -= candidate.weight;
      candidate.remove();
      candidate.appendToTail(headCandidates);
    }
    while (headCandidates.prev != headCandidates) {
      Node candidate = headCandidates.prev;
      candidate.status = Status.PROBATION;
      candidate.remove();
      if ((sizeData + candidate.weight - sizeWindow) > (maximumSize - maxWindow)) {
        Node victim = getVictim();
        if (admit(candidate, victim)) {
          sizeData += candidate.weight;
          while (sizeData > maximumSize) {
            Node evict = getVictim();
            data.remove(evict.key);
            sizeData -= evict.weight;
            if (evict.status == Status.PROTECTED) {
              sizeProtected -= evict.weight;
            }
            evict.remove();
            policyStats.recordEviction();
          }
          candidate.appendToTail(headProbation);
        } else {
          data.remove(candidate.key);
          policyStats.recordEviction();
        }
      } else {
        candidate.appendToTail(headProbation);
        sizeData += candidate.weight;
      }
    }
  }
  
  private boolean admit(Node candidate, Node victim) {
    sketch.reportMiss();

    long candidateFreq = sketch.frequency(candidate.key);
    long victimFreq = sketch.frequency(victim.key);
    switch (version) {
    case SIMPLE:
      if (candidateFreq > victimFreq) {
        policyStats.recordAdmission();
        return true;
      }
      break;
    case SCALED:
      if ( (victim.weight * candidateFreq) > (victimFreq * candidate.weight) ) {
        policyStats.recordAdmission();
        return true;
      }
      break;
    }
    policyStats.recordRejection();
    return false;
  }

  private Node getVictim() {
    if (headProbation.next != headProbation) {
      return headProbation.next;
    }
    return headProtected.next;
  }


  @Override
  public void finished() {
    long windowSize = data.values().stream().filter(n -> n.status == Status.WINDOW).mapToInt(node -> node.weight).sum();
    long probationSize = data.values().stream().filter(n -> n.status == Status.PROBATION).mapToInt(node -> node.weight).sum();
    long protectedSize = data.values().stream().filter(n -> n.status == Status.PROTECTED).mapToInt(node -> node.weight).sum();

    checkState(windowSize <= sizeWindow);
    checkState(protectedSize <= sizeProtected);
    checkState(probationSize <= sizeData - windowSize - protectedSize);

    checkState(sizeData <= maximumSize);
  }

  enum Status {
    WINDOW, PROBATION, PROTECTED
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
      this.key = Integer.MIN_VALUE;
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

  static enum Version {
    SIMPLE,
    SCALED,
  }
  
  public static final class SizedWindowTinyLfuSettings extends WindowTinyLfuSettings {
    public SizedWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public Version version() {
      return Version.valueOf(config().getString("sized-window-tiny-lfu.version").toUpperCase());
    }
  }
}
