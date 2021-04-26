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
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.WindowTinyLfuPolicy.WindowTinyLfuSettings;
import com.typesafe.config.Config;

@PolicySpec(name = "sketch.sized.RistrettoWindowTinyLfu")
public final class RistrettoSizedWindowTinyLfuPolicy extends SizedWindowTinyLfuPolicy {

  public RistrettoSizedWindowTinyLfuPolicy(double percentMain, SizedWindowTinyLfuSettings settings) {
    super(percentMain, settings);
  }
  
  /** Returns all variations of this policy based on the configuration parameters. */
  public static Set<Policy> policies(Config config) {
    SizedWindowTinyLfuSettings settings = new SizedWindowTinyLfuSettings(config);
    return settings.percentMain().stream()
        .map(percentMain -> new RistrettoSizedWindowTinyLfuPolicy(percentMain, settings))
        .collect(toSet());
  }


  @Override
  protected void coreEviction(Node candidate) {
    while ((sizeData + candidate.weight - sizeWindow) > (maximumSize - maxWindow)) {
      Node victim = getVictim();
      victimsCount++;
      if (compare(sketch.frequency(candidate.key), candidate.weight, sketch.frequency(victim.key), victim.weight)) {
        evictNode(victim);
      } else {
        break;
      }
    }
    if ((sizeData + candidate.weight - sizeWindow) <= (maximumSize - maxWindow)) {
      admit(candidate);
    } else {
      reject(candidate);
      if (bump) {
        promote(getVictim());
      }
    }
  }
}
