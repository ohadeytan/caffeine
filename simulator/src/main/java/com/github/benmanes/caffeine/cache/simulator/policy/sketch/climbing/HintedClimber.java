/**
 * 
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.HintedResetCountMin4.Hinter;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.Config;

/**
 * @author ohad
 *
 */
public final class HintedClimber implements HillClimber {

	private final Hinter h;
	private final PeriodicResetCountMin4 shadowSketch;
//	private final double[] hintToPercent = {0, 0, 1, 2, 4, 8, 16, 32, 68, 84, 92, 96, 98, 99, 100, 100};
//	private final double[] hintToPercent = {0, 1, 1, 1, 1, 2, 4, 8, 16, 32, 68, 84, 92, 96, 98, 99};
	private final double[] hintToPercent = {0, 1, 1, 1, 1, 2, 3, 5, 7, 10, 12, 15, 17, 20, 25, 30};
	private double prevPercent;
	private int cacheSize;
	private int sample;
	
	public HintedClimber(Config config) {
		this.shadowSketch = new PeriodicResetCountMin4(config);
		this.h = new Hinter();
		
	    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
		this.prevPercent = 1 - settings.percentMain().get(0);
		this.cacheSize = settings.maximumSize();
	}
	
	@Override
	public void onHit(long key, QueueType queue) {
		int hint = shadowSketch.frequency(key);
		if (hint > 0) {
			h.increment(hint);
		}
		shadowSketch.increment(key);
	}

	@Override
	public void onMiss(long key) {
		int hint = shadowSketch.frequency(key);
		if (hint > 0) {
			h.increment(hint);
		}
		shadowSketch.increment(key);
	}

	@Override
	public Adaptation adapt(int windowSize, int protectedSize) {
		sample++;
		if (sample == cacheSize*10) {
			double oldPercent = prevPercent;
			double newPercent = prevPercent = hintToPercent[h.getAverage()] / 100.0;

			h.reset();
			sample = 0;
			if (newPercent > oldPercent) {
				return new Adaptation(Adaptation.Type.INCREASE_WINDOW, (int)((newPercent - oldPercent)*cacheSize));
			}
			return new Adaptation(Adaptation.Type.DECREASE_WINDOW, (int)((oldPercent - newPercent)*cacheSize));
		}
		return new Adaptation(Adaptation.Type.HOLD, 0);
	}
}
