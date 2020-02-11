/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sized;

import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.DECREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation.Type.INCREASE_WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROBATION;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.PROTECTED;
import static com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType.WINDOW;
import static com.github.benmanes.caffeine.cache.simulator.policy.Policy.Characteristic.WEIGHTED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.checkerframework.checker.nullness.qual.Nullable;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.policy.AccessEvent;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.QueueType;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberType;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Sets;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * The Window TinyLfu algorithm where the size of the admission window is adjusted using the a hill
 * climbing algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@SuppressWarnings("PMD.TooManyFields")
public class SizedHillClimberWindowTinyLfuPolicy implements Policy {
  protected final double initialPercentMain;
  protected final HillClimberType strategy;
  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final HillClimber climber;
  protected final PeriodicResetCountMin4 sketch;
  protected final long maximumSize;

  private final Node headWindow;
  protected final Node headProbation;
  protected final Node headProtected;

  protected long maxWindow;
  private long maxProtected;
  private boolean isFull;
  
  protected long windowSize;
  private long protectedSize;
  protected long sizeData;

  static final boolean debug = false;
  static final boolean trace = false;

  public SizedHillClimberWindowTinyLfuPolicy(HillClimberType strategy, double percentMain,
      HillClimberWindowTinyLfuSettings settings) {

    long maxMain = (long) (settings.maximumSizeLong() * percentMain);
    this.maxProtected = (long) (maxMain * settings.percentMainProtected());
    this.maxWindow = settings.maximumSizeLong() - maxMain;
    this.data = new Long2ObjectOpenHashMap<>();
    this.maximumSize = settings.maximumSizeLong();
    this.headProtected = new Node();
    this.headProbation = new Node();
    this.headWindow = new Node();
    this.isFull = false;
    
    this.strategy = strategy;
    this.initialPercentMain = percentMain;
    this.policyStats = new PolicyStats(getPolicyName());
    this.sketch = new PeriodicResetCountMin4(settings.config());
    this.climber = strategy.create(settings.config());

    printSegmentSizes();
  }

  public String getPolicyName() {
    return String.format("sketch.sized.HillClimberWindowTinyLfu (%s %.0f%% -> %.0f%%)",
        strategy.name().toLowerCase(US), 100 * (1.0 - initialPercentMain),
        (100.0 * maxWindow) / maximumSize);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    Set<Policy> policies = new HashSet<>();
    for (HillClimberType climber : settings.strategy()) {
      for (double percentMain : settings.percentMain()) {
        policies.add(new SizedHillClimberWindowTinyLfuPolicy(climber, percentMain, settings));
      }
    }
    return policies;
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }


  @Override 
  public Set<Characteristic> characteristics() {
    return Sets.immutableEnumSet(WEIGHTED);
  }
  
  @Override
  public void record(AccessEvent event) {
    final long key = event.key();
    final int weight = event.weight();
    policyStats.recordOperation();
    Node node = data.get(key);
    if (sizeData >= (maximumSize >>> 1)) {
      sketch.ensureCapacity(data.size());
      if ((sizeData + weight) >= maximumSize) {
        isFull = true;
      }
    }
    sketch.increment(key);

    QueueType queue = null;
    if (node == null) {
      onMiss(key, weight);
      policyStats.recordWeightedMiss(weight);
    } else {
      queue = node.queue;
      policyStats.recordWeightedHit(weight);
      if (queue == WINDOW) {
        onWindowHit(node);
      } else if (queue == PROBATION) {
        onProbationHit(node);
      } else if (queue == PROTECTED) {
        onProtectedHit(node);
      } else {
        throw new IllegalStateException();
      }
    }
    climb(key, queue, isFull);
  }

  /** Adds the entry to the admission window, evicting if necessary. */
  private void onMiss(long key, int weight) {
    if (weight > (maximumSize - maxWindow)) {
      policyStats.recordRejection();
      return;
    }

    Node node = new Node(key, weight, WINDOW);
    if (weight > maxWindow) {
      node.appendToHead(headWindow);  
    } else {
      node.appendToTail(headWindow);
    }
    data.put(key, node);
    windowSize += weight;
    sizeData += weight;
    evict();
  }

  /** Moves the entry to the MRU position in the admission window. */
  private void onWindowHit(Node node) {
    node.moveToTail(headWindow);
  }

  /** Promotes the entry to the protected region's MRU position, demoting an entry if necessary. */
  private void onProbationHit(Node node) {
    node.remove();
    node.queue = PROTECTED;
    node.appendToTail(headProtected);
    protectedSize += node.weight;
    demoteProtected();
  }

  private void demoteProtected() {
    while (protectedSize > maxProtected) {
      Node demote = headProtected.next;
      demote.remove();
      demote.queue = PROBATION;
      demote.appendToTail(headProbation);
      protectedSize -= demote.weight;
    }
  }

  /** Moves the entry to the MRU position, if it falls outside of the fast-path threshold. */
  private void onProtectedHit(Node node) {
    node.moveToTail(headProtected);
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
      if ((sizeData + candidate.weight - windowSize) > (maximumSize - maxWindow)) {
        coreEviction(candidate);
      } else {
        admit(candidate);
      }
    }
  }
  
  protected void coreEviction(Node candidate) {
    Node victim = getVictim();
    if (compare(sketch.frequency(candidate.key), candidate.weight, sketch.frequency(victim.key), victim.weight)) {
      while ((sizeData + candidate.weight - windowSize) > (maximumSize - maxWindow)) {
        Node evict = getVictim();
        evictNode(evict);
      }
      admit(candidate);
    } else {
      reject(candidate);
    }    
  }
  
  protected boolean compare(int candidateFreq, int candidateWeight, int victimFreq, int victimWeight) {
    return candidateFreq > victimFreq;
  }
  
  protected void admit(Node candidate) {
    candidate.appendToTail(headProbation);
    sizeData += candidate.weight;
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
    if (evict.queue == PROTECTED) {
      protectedSize -= evict.weight;
    }
    evict.remove();
    policyStats.recordEviction();    
  }

  /**
   * Collect candidates  for eviction from the Window
   */
  private void collectCandidates(final Node headCandidates) {
    while (windowSize > maxWindow) {
      Node candidate = headWindow.next;
      windowSize -= candidate.weight;
      sizeData -= candidate.weight;
      candidate.remove();
      if (candidate.weight > (maximumSize - maxWindow)) {
        reject(candidate);
      } else {
        candidate.queue = PROBATION;
        candidate.appendToTail(headCandidates);
      }
    }
  }
  
  protected Node getVictim() {
    if (headProbation.next != headProbation) {
      return headProbation.next;
    }
    return headProtected.next;
  }
  
  /** Performs the hill climbing process. */
  private void climb(long key, @Nullable QueueType queue, boolean isFull) {
    if (queue == null) {
      climber.onMiss(key, isFull);
    } else {
      climber.onHit(key, queue, isFull);
    }

    double probationSize = maximumSize - windowSize - protectedSize;
    if (isFull && sketch.isGoingToReset()) {
      Adaptation adaptation = climber.adapt(windowSize, probationSize, protectedSize, isFull);
      if (adaptation.type == INCREASE_WINDOW) {
        increaseWindow(adaptation.amount);
      } else if (adaptation.type == DECREASE_WINDOW) {
        decreaseWindow(adaptation.amount);
      }
    }
  }

  private void increaseWindow(double amount) {
    checkState(amount >= 0.0);
    if (maxProtected <= 0) {
      return;
    }
    
    long quota = Math.min((long) amount, maxProtected);

    maxWindow += quota;
    maxProtected -= quota;

    demoteProtected();
    while ((sizeData - windowSize) > (maximumSize - maxWindow)) {
      Node candidate = getVictim();
      if (candidate.queue == PROTECTED) {
        protectedSize -= candidate.weight;
      }
      candidate.queue = WINDOW;
      candidate.remove();
      candidate.appendToHead(headWindow);
      windowSize += candidate.weight;
    }
    evict();
    
    checkState(windowSize >= 0);
    checkState(maxWindow >= 0);
    checkState(maxProtected >= 0);
    checkState(sizeData <= maximumSize);
    checkState(windowSize <= maxWindow);

    if (trace) {
      System.out.printf("+%,d (%,d -> %,d)%n", (int) quota, maxWindow - (int) quota, maxWindow);
    }
  }

  private void decreaseWindow(double amount) {
    checkState(amount >= 0.0);
    if (maxWindow <= 0) {
      return;
    }
    
    long quota = Math.min((long) amount, maxWindow);
    checkState(quota >= 0);
    maxWindow -= quota;
    maxProtected += quota;

    Node candidate = headWindow.next;
    while ((windowSize > maxWindow) && (sizeData - windowSize + candidate.weight) <= (maximumSize - maxWindow)) {
      candidate.queue = PROBATION;
      candidate.remove();
      candidate.appendToHead(headProbation);
      windowSize -= candidate.weight;
      candidate = headWindow.next;
    }
    evict();
    
    checkState(windowSize >= 0);
    checkState(maxWindow >= 0);
    checkState(maxProtected >= 0);
    checkState(sizeData <= maximumSize);
    checkState(windowSize <= maxWindow);

    if (trace) {
      System.out.printf("-%,d (%,d -> %,d)%n", (int) quota, maxWindow + (int) quota, maxWindow);
    }
  }

  private void printSegmentSizes() {
    if (debug) {
      System.out.printf("maxWindow=%d, maxProtected=%d, percentWindow=%.1f",
          maxWindow, maxProtected, (100.0 * maxWindow) / maximumSize);
    }
  }

  @Override
  public void finished() {    
    policyStats.setName(getPolicyName());
    printSegmentSizes();

    long actualWindowSize = data.values().stream().filter(n -> n.queue == WINDOW).mapToLong(node -> node.weight).sum();
    long actualProbationSize = data.values().stream().filter(n -> n.queue == PROBATION).mapToLong(node -> node.weight).sum();
    long actualProtectedSize = data.values().stream().filter(n -> n.queue == PROTECTED).mapToLong(node -> node.weight).sum();
    long calculatedProbationSize = sizeData - actualWindowSize - actualProtectedSize;

    checkState((long) windowSize == actualWindowSize,
        "Window: %s != %s", (long) windowSize, actualWindowSize);
    checkState((long) protectedSize == actualProtectedSize,
        "Protected: %s != %s", (long) protectedSize, actualProtectedSize);
    checkState(actualProbationSize == calculatedProbationSize,
        "Probation: %s != %s", actualProbationSize, calculatedProbationSize);
    checkState(sizeData <= maximumSize, "Maximum: %s > %s", sizeData, maximumSize);
  }

  /** A node on the double-linked list. */
  static final class Node {
    final long key;
    final int weight;

    QueueType queue;
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
    public Node(long key, int weight, QueueType queue) {
      this.queue = queue;
      this.key = key;
      this.weight = weight;
    }

    public void moveToTail(Node head) {
      remove();
      appendToTail(head);
    }

    /** Appends the node to the tail of the list. */
    public void appendToHead(Node head) {
      Node first = head.next;
      head.next = this;
      first.prev = this;
      prev = head;
      next = first;
    }

    /** Appends the node to the tail of the list. */
    public void appendToTail(Node head) {
      Node tail = head.prev;
      head.prev = this;
      tail.next = this;
      next = head;
      prev = tail;
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
          .add("queue", queue)
          .toString();
    }
  }

  public static final class HillClimberWindowTinyLfuSettings extends BasicSettings {
    public HillClimberWindowTinyLfuSettings(Config config) {
      super(config);
    }
    public List<Double> percentMain() {
      return config().getDoubleList("hill-climber-window-tiny-lfu.percent-main");
    }
    public double percentMainProtected() {
      return config().getDouble("hill-climber-window-tiny-lfu.percent-main-protected");
    }
    public Set<HillClimberType> strategy() {
      return config().getStringList("hill-climber-window-tiny-lfu.strategy").stream()
          .map(strategy -> strategy.replace('-', '_').toUpperCase(US))
          .map(HillClimberType::valueOf)
          .collect(toSet());
    }

  }
}
