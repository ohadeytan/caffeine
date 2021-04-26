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
import com.github.benmanes.caffeine.cache.simulator.policy.Policy.PolicySpec;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberType;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sized.SizedHillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.sized.SizedHillClimberWindowTinyLfuPolicy.Node;
import com.typesafe.config.Config;

@PolicySpec(name = "sketch.sized.RistrettoHillClimberWindowTinyLfu")
public final class RistrettoSizedHillClimberWindowTinyLfuPolicy extends SizedHillClimberWindowTinyLfuPolicy {

  public RistrettoSizedHillClimberWindowTinyLfuPolicy(HillClimberType strategy, double percentMain,
      HillClimberWindowTinyLfuSettings settings) {
    super(strategy, percentMain, settings);
  }

  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
    Set<Policy> policies = new HashSet<>();
    for (HillClimberType climber : settings.strategy()) {
      for (double percentMain : settings.percentMain()) {
        policies.add(new RistrettoSizedHillClimberWindowTinyLfuPolicy(climber, percentMain, settings));
      }
    }
    return policies;
  }

  @Override
  public String getPolicyName() {
    return String.format("sketch.sized.RistrettoHillClimberWindowTinyLfu (%s %.0f%% -> %.0f%%)",
        strategy.name().toLowerCase(US), 100 * (1.0 - initialPercentMain),
        (100.0 * maxWindow) / maximumSize);
  }

  @Override
  protected void coreEviction(Node candidate) {
    while ((sizeData + candidate.weight - windowSize) > (maximumSize - maxWindow)) {
      Node victim = getVictim();
      if (compare(sketch.frequency(candidate.key), candidate.weight, sketch.frequency(victim.key), victim.weight)) {
        evictNode(victim);
      } else {
        break;
      }
    }
    if ((sizeData + candidate.weight - windowSize) <= (maximumSize - maxWindow)) {
      admit(candidate);
    } else {
      reject(candidate);
    }
  }
}
