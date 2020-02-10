package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.hill;

import static com.google.common.base.Preconditions.checkState;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimber.Adaptation;
import com.typesafe.config.Config;

public class TriggeredClimber extends SimpleClimber {

  public TriggeredClimber(Config config) {
    super(config);
  }
  
  @Override
  public Adaptation adapt(double windowSize, double probationSize,
      double protectedSize, boolean isFull) {
    checkState(sampleSize > 0, "Sample size may not be zero");
    int sampleCount = (hitsInSample + missesInSample);

    double hitRate = (double) hitsInSample / sampleCount;
    Adaptation adaption = Adaptation.adaptBy(adjust(hitRate));
    resetSample(hitRate);
    return adaption;
  }
}
