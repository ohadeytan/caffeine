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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.sized;

import static java.util.stream.Collectors.toSet;

import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.typesafe.config.Config;

public final class SumSizedWindowTinyLfuPolicy extends SizedWindowTinyLfuPolicy {

  public SumSizedWindowTinyLfuPolicy(double percentMain, WindowTinyLfuSettings settings) {
    super(percentMain, settings);
    String name = String.format("sketch.sized.SumWindowTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    policyStats.setName(name);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    WindowTinyLfuSettings settings = new WindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new SumSizedWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }

  
  @Override
  protected void coreEviction(Node candidate) {
    int candidateFreq = sketch.frequency(candidate.key);
    long sizeNeeded = (sizeData + candidate.weight - sizeWindow) - maxMain;
    int victimsSize = 0;
    int victimsNum = 0;
    int victimsFreq = 0;
    Node victim = headProbation;
    while (victimsSize < sizeNeeded) {
      if (victim.next != headProbation) {
        victim = victim.next;
      } else {
        victim = headProtected.next;
      }
      victimsSize += victim.weight;
      victimsNum++;
      victimsFreq += sketch.frequency(victim.key);
      if (victimsFreq > candidateFreq) {
        break;
      }
    }
    if (victimsFreq > candidateFreq) {
       reject(candidate);
    } else {
      for (int i = 0; i < victimsNum; i++) {
        Node evict = getVictim();
        evictNode(evict);
      }
      admit(candidate);
    }    
  }
}