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
	Counter c;
	int prevMedian;
	List<Integer> replay;
	boolean median;
	
	public HintedResetCountMin4(Config config) {
		super();
		this.sketch = new AdaptiveResetCountMin4(config);
		this.shadowSketch = new PeriodicResetCountMin4(config);
		this.c = new Counter();
		
	    BasicSettings settings = new BasicSettings(config);
		this.replay = settings.tinyLfu().countMin4().replay();
		this.median = settings.tinyLfu().countMin4().median();
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
	    	hintSum += hint;
	    	hintCount++;
		    c.increment(hint);
	    }
	    shadowSketch.increment(e);
	}

	@Override
	public void reportMiss() {
	  if (sketch.getEventsToCount() <= 0) {
		  sketch.resetEventsToCount();
		  int hint;
		  if (!replay.isEmpty()) {
			  hint = replay.get(0);
			  replay.remove(0);
		  } else if (median) {
			  hint = c.getMedian();
		  } else {
			 hint = hintSum / hintCount;
		  }
		  hint = (hint < 4) ? 0 : hint - 4;
		  sketch.setStep(1 << hint);
		  prevHintSum = hintSum;
		  prevHintCount = hintCount;
		  prevMedian = c.getMedian();
		  hintSum = 0;
		  hintCount = 0;
		  c.reset();
		  
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
	
	private class Counter {
		int[] freq = new int[16];
		
		public Counter() {}
		
		public void increment(int i) {
			freq[i]++;
		}
		
		public void reset() {
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
	}

}
