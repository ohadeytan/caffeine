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
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sized;

import static java.util.Locale.US;
import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.Set;

import com.github.benmanes.caffeine.cache.simulator.policy.Policy;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberType;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sized.SizedHillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.Config;

public final class SumSizedHillClimberWindowTinyLfuPolicy extends SizedHillClimberWindowTinyLfuPolicy {

  public SumSizedHillClimberWindowTinyLfuPolicy(HillClimberType strategy, double percentMain,
      HillClimberWindowTinyLfuSettings settings) {
    super(strategy, percentMain, settings);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    Set<Policy> policies = new HashSet<>();
    for (HillClimberType climber : settings.strategy()) {
      for (double percentMain : settings.percentMain()) {
        policies.add(new SumSizedHillClimberWindowTinyLfuPolicy(climber, percentMain, settings));
      }
    }
    return policies;
  }

  @Override
  public String getPolicyName() {
    return String.format("sketch.sized.SumHillClimberWindowTinyLfu (%s %.0f%% -> %.0f%%)",
        strategy.name().toLowerCase(US), 100 * (1.0 - initialPercentMain),
        (100.0 * maxWindow) / maximumSize);
  }

  
  @Override
  protected void coreEviction(Node candidate) {
    int candidateFreq = sketch.frequency(candidate.key);
    long sizeNeeded = (sizeData + candidate.weight - windowSize) - (maximumSize - maxWindow);
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
    }
    if (!compare(candidateFreq, candidate.weight, victimsFreq, victimsSize)) {
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
