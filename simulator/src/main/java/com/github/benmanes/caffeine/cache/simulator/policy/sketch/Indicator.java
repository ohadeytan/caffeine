package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.IntStream;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

public class Indicator {
	private final Hinter hinter;
	private final EstSkew estSkew;
	private final PeriodicResetCountMin4 sketch;
	private long sample;
	
	public Indicator(Config config) {
		super();
		this.hinter = new Hinter();
		this.estSkew = new EstSkew();
		this.sketch = new PeriodicResetCountMin4(config);
		this.sample = 0;
	}

	public void record(long key) {
	    int hint = sketch.frequency(key);
	    if (hint > 0) {
		    hinter.increment(hint);
	    }
	    sketch.increment(key);
	    estSkew.record(key);
	    sample++;
	}
	
	public void reset() {
		hinter.reset();
		estSkew.reset();
		sample = 0;
	}
	
	public long getSample() {
		return sample;
	}
	
	public double getSkew() {
		return estSkew.estSkew(50);
	}
	
	public double getHint() {
		return hinter.getAverage();
	}
	
	private static class Hinter {
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
		
		public double getAverage() {
			return ((double) sum) / count;
		}
		
		public int getSum() {
			return sum;
		}
		
		public int getCount() {
			return count;
		}
	}

	private static class EstSkew {
		Long2IntMap freq;
		public EstSkew() {
			this.freq = new Long2IntOpenHashMap();
			this.freq.defaultReturnValue(0);
		}
		
		public void record(long key) {
			freq.put(key, freq.get(key) + 1);
		}
		
		public void reset() {
			freq.clear();
		}
		
		public IntStream getTopK(int k) {
			return freq.values().stream().sorted(Collections.reverseOrder()).mapToInt(i->i).limit(k);
		}	
		
		public double estSkew(int k) {
			SimpleRegression regression = new SimpleRegression();
			int[] idx = {1};
			getTopK(k).forEachOrdered(freq -> regression.addData(Math.log(idx[0]++), Math.log(freq)));
			return -regression.getSlope();
		}
	}
}
