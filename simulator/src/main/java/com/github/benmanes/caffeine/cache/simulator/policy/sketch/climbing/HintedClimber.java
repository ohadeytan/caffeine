/**
 * 
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.Config;

/**
 * @author ohad
 *
 */
public final class HintedClimber implements HillClimber {

	private final Indicator indicator;
//	private final double[] hintToPercent = {0, 0, 1, 2, 4, 8, 16, 32, 68, 84, 92, 96, 98, 99, 100, 100};
//	private final double[] hintToPercent = {0, 1, 1, 1, 1, 2, 4, 8, 16, 32, 68, 84, 92, 96, 98, 99};
	private final double[] hintToPercent = {0, 1, 1, 1, 1, 2, 3, 5, 7, 10, 12, 15, 17, 20, 25, 30};
	private double prevPercent;
	private int cacheSize;
	
	public HintedClimber(Config config) {
	    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
		this.prevPercent = 1 - settings.percentMain().get(0);
		this.cacheSize = settings.maximumSize();
		this.indicator = new Indicator(config);
	}
	
	@Override
	public void onHit(long key, QueueType queue) {
		indicator.record(key);
	}

	@Override
	public void onMiss(long key) {
		indicator.record(key);
	}

	@Override
	public Adaptation adapt(int windowSize, int protectedSize) {
//		if (indicator.getSample() == cacheSize*10) {
		if (indicator.getSample() == 50000) {
			double oldPercent = prevPercent;
			double skew = indicator.getSkew();
			double ind = indicator.getIndicator();
			double newPercent;
			if (ind < 0.2) {
				newPercent = prevPercent = ind*80 / 100.0;
			} else {
				newPercent = prevPercent = ind*80 / 100.0;
			}
			sumHint += indicator.getHint();
			sumSkew += Math.floor(skew*1000);
			sumPercent += Math.floor(newPercent*100);
			sumGini += indicator.getGini();		
			sumIndicator += Math.floor(ind*1000);
			sumMaximal += indicator.getMaximal();
			periods++;
			indicator.reset();
			if (newPercent > oldPercent) {
				return new Adaptation(Adaptation.Type.INCREASE_WINDOW, (int)((newPercent - oldPercent)*cacheSize));
			}
			return new Adaptation(Adaptation.Type.DECREASE_WINDOW, (int)((oldPercent - newPercent)*cacheSize));
		}
		return new Adaptation(Adaptation.Type.HOLD, 0);
	}
	
	long sumHint;
	long sumSkew;
	long sumPercent;
	long sumGini;
	long sumIndicator;
	long sumMaximal;
	long periods;

	public long getSumHint() {
		return sumHint;
	}

	public long getSumSkew() {
		return sumSkew;
	}

	public long getSumPercent() {
		return sumPercent;
	}

	public long getSumGini() {
		return sumGini;
	}

	public long getSumIndicator() {
		return sumIndicator;
	}

	public long getPeriods() {
		return periods;
	}

	public Long getSumMaximal() {
		return sumMaximal;
	}
	
}
