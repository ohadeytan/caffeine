package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.typesafe.config.Config;

public class HintedResetCountMin4 implements Frequency {
	private final AdaptiveResetCountMin4 sketch;
	private final PeriodicResetCountMin4 shadowSketch;
	
	int hintSum;
	int hintCount;
	
	public HintedResetCountMin4(Config config) {
		super();
		this.sketch = new AdaptiveResetCountMin4(config);
		this.shadowSketch = new PeriodicResetCountMin4(config);
	}

	@Override
	public int frequency(long e) {
		return sketch.frequency(e);
	}

	@Override
	public void increment(long e) {
		sketch.increment(e);
	    int hint = shadowSketch.frequency(e);
	    if (hint > 0) {
	    	hintSum += hint;
	    	hintCount++;
	    }
	    shadowSketch.increment(e);
	}

	@Override
	public void reportMiss() {
	  if (sketch.getEventsToCount() <= 0) {
		  sketch.resetEventsToCount();
		  int hint = hintSum / hintCount;
		  hint = (hint < 4) ? 0 : hint - 4;
		  sketch.setStep(1 << hint);
		  hintSum = 0;
		  hintCount = 0;
	  }
	}
}
