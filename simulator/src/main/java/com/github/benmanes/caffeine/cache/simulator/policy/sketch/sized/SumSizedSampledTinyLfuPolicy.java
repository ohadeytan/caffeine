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
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.typesafe.config.Config;

@PolicySpec(name = "sketch.sized.SumSampledTinyLfu")
public final class SumSizedSampledTinyLfuPolicy extends SizedSampledTinyLfuPolicy {

  public SumSizedSampledTinyLfuPolicy(double percentMain, SizedWindowTinyLfuSettings settings) {
    super(percentMain, settings);
    //String name = String.format("sketch.sized." + "SumSampledTinyLfu (%.0f%%)", 100 * (1.0d - percentMain));
    //policyStats.setName(name);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    SizedWindowTinyLfuSettings settings = new SizedWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new SumSizedSampledTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }
  
  @Override
  protected void coreEviction(Node candidate) {
    final Node headVictimsNode = new Node();
    int candidateFreq = sketch.frequency(candidate.key);
    long sizeNeeded = (sizeData + candidate.weight - sizeWindow) - maxMain;
    int victimsSize = 0;
    int victimsNum = 0;
    int victimsFreq = 0;
    while (victimsSize < sizeNeeded) {
      Node victim = getVictim(sizeNeeded - victimsSize);
      victim.appendToTail(headVictimsNode);
      victimsSize += victim.weight;
      victimsNum++;
      victimsFreq += sketch.frequency(victim.key);
      if ((!scaled) && prune && victimsFreq > candidateFreq) {
        break;
      }
    }
    victimsCount += victimsNum;
    if (!compare(candidateFreq, candidate.weight, victimsFreq, victimsSize)) {
       reject(candidate);
       for (int i = 0; i < victimsNum; i++) {
         main.add(headVictimsNode.next.key);
         headVictimsNode.next.remove();
       }
    } else {
      for (int i = 0; i < victimsNum; i++) {
        evictNode(headVictimsNode.next);
        headVictimsNode.next.remove();
      }
      admit(candidate);
    }    
  }
}
