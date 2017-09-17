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
package com.github.benmanes.caffeine.cache.simulator.policy.adaptive;

import static com.google.common.base.Preconditions.checkState;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class ArcIndicatorPolicy implements Policy {
  // In Cache:
  // - T1: Pages that have been accessed at least once
  // - T2: Pages that have been accessed at least twice

  private final Long2ObjectMap<Node> data;
  private final PolicyStats policyStats;
  private final int maximumSize;

  private final Node headT1;
  private final Node headT2;

  private int sizeT1;
  private int sizeT2;
  private int p;
  
  private final Indicator indicator;

  public ArcIndicatorPolicy(Config config) {
    BasicSettings settings = new BasicSettings(config);
    this.policyStats = new PolicyStats("adaptive.ArcIndicator");
    this.maximumSize = settings.maximumSize();
    this.data = new Long2ObjectOpenHashMap<>();
    this.headT1 = new Node();
    this.headT2 = new Node();
    this.p = maximumSize / 4;
    this.indicator = new Indicator(config);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    return ImmutableSet.of(new ArcIndicatorPolicy(config));
  }

  @Override
  public void record(long key) {
    policyStats.recordOperation();
    
	adapt(key);
	
    Node node = data.get(key);
    if (node == null) {
      onMiss(key);
    } else {
      onHit(node);
    }
  }
  
  private void adapt(long key) {
	indicator.record(key);
	if (indicator.getSample() == maximumSize*10) {
		double skew = indicator.getSkew();
		p = (int) (maximumSize*(0.3 - (4 - indicator.getHint()*(skew < 1 ? 1 - Math.pow(skew, 3) : 0)) / 100.0));
		p = p > maximumSize ? maximumSize : p;
		p = p > 0 ? p : 0;
		indicator.reset();
	}
  }

  private void onHit(Node node) {
    // x ∈ T1 ∪ T2: Move x to the top of T2
    if (node.type == QueueType.T1) {
      sizeT1--;
      sizeT2++;
    }
    node.remove();
    node.type = QueueType.T2;
    node.appendToTail(headT2);
    policyStats.recordHit();
  }

  private void onMiss(long key) {
    // x ∉ T1 ∪ T2: Put x at the top of T1 and place it in the cache.
    // case (i) |T1| =< p:
	//   if |T1| + |T2| > c:
	//     delete LRU of T2
    // case (ii) |T1| > p:
	//   if |T1| + |T2| > c:
	//     delete LRU of T1
	  
    Node node = new Node(key);
    node.type = QueueType.T1;
    sizeT1++;
    data.put(key, node);
    node.appendToTail(headT1);
    
    if (sizeT1 <= p) {
    	if (sizeT1 + sizeT2 > maximumSize) {
    		Node victim = headT2.next;
    		data.remove(victim.key);
    		victim.remove();
    		sizeT2--;
    	}
    }
    
    if (sizeT1 > p) {
    	if (sizeT1 + sizeT2 > maximumSize) {
    		Node victim = headT1.next;
    		data.remove(victim.key);
    		victim.remove();
    		sizeT1--;
    	}
    }    
    policyStats.recordMiss();
  }

  @Override
  public PolicyStats stats() {
    return policyStats;
  }

  @Override
  public void finished() {
    checkState(sizeT1 == data.values().stream().filter(node -> node.type == QueueType.T1).count());
    checkState(sizeT2 == data.values().stream().filter(node -> node.type == QueueType.T2).count());
//    checkState(sizeB1 == data.values().stream().filter(node -> node.type == QueueType.B1).count());
//    checkState(sizeB2 == data.values().stream().filter(node -> node.type == QueueType.B2).count());
    checkState((sizeT1 + sizeT2) <= maximumSize);
//    checkState((sizeB1 + sizeB2) <= maximumSize);
  }

  private enum QueueType {
    T1, 
    T2, 
  }

  static final class Node {
    final long key;

    Node prev;
    Node next;
    QueueType type;

    Node() {
      this.key = Long.MIN_VALUE;
      this.prev = this;
      this.next = this;
    }

    Node(long key) {
      this.key = key;
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
      checkState(key != Long.MIN_VALUE);
      prev.next = next;
      next.prev = prev;
      prev = next = null;
      type = null;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("key", key)
          .add("type", type)
          .toString();
    }
  }
}
