package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.typesafe.config.Config;

public class HintedResetCountMin4 implements Frequency {
	private final AdaptiveResetCountMin4 sketch;
	
	int hintSum;
	int hintCount;
	int prevHintSum;
	int prevHintCount;
	Indicator indicator;
	int prevMedian;
	List<Integer> replay;
	boolean median;
	int formula;
	
	public HintedResetCountMin4(Config config) {
		super();
		this.sketch = new AdaptiveResetCountMin4(config);
		this.indicator = new Indicator(config);
		
	    BasicSettings settings = new BasicSettings(config);
		this.replay = settings.tinyLfu().countMin4().replay();
		this.median = settings.tinyLfu().countMin4().median();
		this.formula = settings.tinyLfu().countMin4().formula();
		int hint;
		if (!replay.isEmpty()) {
			hint = replay.get(0);
			replay.remove(0);
			hint = (hint < 4) ? 0 : hint - 4;
			sketch.setStep(1 << hint);
		}

	}

	@Override
	public int frequency(long e) {
		return sketch.frequency(e);
	}

	@Override
	public void increment(long e) {
		sketch.increment(e);
		indicator.record(e);
	}

	@Override
	public void reportMiss() {
	  if (sketch.getEventsToCount() <= 0) {
		  sketch.resetEventsToCount();
		  double ind = getIndicator();
		  sketch.setStep(hintToStep(ind));
		  indicator.reset();
	  }
	}
	
	private double getIndicator() {
		if (!replay.isEmpty()) {
			return replay.remove(0);
//		} else if (median) {
//			return h.getMedian();
		} else {
			return indicator.getIndicator();
		}
	}
	
	private int hintToStep(double ind) {
		ind = ind*15;
		switch (formula) {
//		case 1:
//			ind = (ind < 4) ? 0 : ind - 4;
//			return 1 << ind;
		case 2:
//			return (int) ((ind < 2) ? 1 : 2*(ind - 2));
			return (int) (2*ind);
//		case 3:
//			return Math.min((ind < 4) ? 1 : 2*(ind - 4), 14); 
//		case 4:
//			ind = (ind < 4) ? 0 : ind - 4;
//			return Math.min(1 << ind, 14);
		default:
			return 1;
		}
	}
	
    public int getEventsToCount() {
		return sketch.getEventsToCount();
	}

	public int getPeriod() {
		return sketch.getPeriod();
	}

	public int getHintSum() {
		return prevHintSum;
	}

	public int getHintCount() {
		return prevHintCount;
	}
	
	public int getMedianHint() {
		return prevMedian;
	}	
}
