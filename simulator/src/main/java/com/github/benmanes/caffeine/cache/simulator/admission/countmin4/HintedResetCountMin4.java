package com.github.benmanes.caffeine.cache.simulator.admission.countmin4;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.Frequency;
import com.typesafe.config.Config;

public class HintedResetCountMin4 implements Frequency {
	private final AdaptiveResetCountMin4 sketch;
	private final PeriodicResetCountMin4 shadowSketch;
	
	int hintSum;
	int hintCount;
	int prevHintSum;
	int prevHintCount;
	Hinter h;
	int prevMedian;
	List<Integer> replay;
	boolean median;
	int formula;
	
	public HintedResetCountMin4(Config config) {
		super();
		this.sketch = new AdaptiveResetCountMin4(config);
		this.shadowSketch = new PeriodicResetCountMin4(config);
		this.h = new Hinter();
		
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
	    int hint = shadowSketch.frequency(e);
	    if (hint > 0) {
		    h.increment(hint);
	    }
	    shadowSketch.increment(e);
	}

	@Override
	public void reportMiss() {
	  if (sketch.getEventsToCount() <= 0) {
		  sketch.resetEventsToCount();
		  int hint = getHint();
		  sketch.setStep(hintToStep(hint));
		  prevHintSum = h.getSum();
		  prevHintCount = h.getCount();
		  prevMedian = h.getMedian();
		  h.reset();
	  }
	}
	
	private int getHint() {
		if (!replay.isEmpty()) {
			return replay.remove(0);
		} else if (median) {
			return h.getMedian();
		} else {
			return h.getAverage();
		}
	}
	
	private int hintToStep(int hint) {
		switch (formula) {
		case 1:
			hint = (hint < 4) ? 0 : hint - 4;
			return 1 << hint;
		case 2:
			return (hint < 4) ? 1 : 2*(hint - 4);
		case 3:
			return Math.min((hint < 4) ? 1 : 2*(hint - 4), 14); 
		case 4:
			hint = (hint < 4) ? 0 : hint - 4;
			return Math.min(1 << hint, 14);
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
	
	private class Hinter {
		int sum;
		int count;
		int[] freq = new int[16];
		
		public Hinter() {}
		
		public void increment(int i) {
			sum += i;
			count++;
			freq[i]++;
		}
		
		public void reset() {
			sum = count = 0;
			Arrays.fill(freq, 0);
		}
		
		public int getMedian() {
			int mid = (1+IntStream.of(freq).sum()) / 2;
			int count = 0;
			for (int i = 0; i < 16; i++) {
				count += freq[i];
				if (count >= mid) {
					return i;
				}
			}
			return 0;
		}
		
		public int getAverage() {
			return sum / count;
		}
		
		public int getSum() {
			return sum;
		}
		
		public int getCount() {
			return count;
		}
	}

}
