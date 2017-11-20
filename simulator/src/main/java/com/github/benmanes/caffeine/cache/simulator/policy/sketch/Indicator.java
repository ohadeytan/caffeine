package com.github.benmanes.caffeine.cache.simulator.policy.sketch;

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.IntStream;

import org.apache.commons.math3.stat.regression.SimpleRegression;

import com.github.benmanes.caffeine.cache.simulator.BasicSettings;
import com.github.benmanes.caffeine.cache.simulator.BasicSettings.TinyLfuSettings.DoorkeeperSettings;
import com.github.benmanes.caffeine.cache.simulator.admission.countmin4.PeriodicResetCountMin4;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

public class Indicator {
	private final Hinter hinter;
	private final EstSkew estSkew;
	private final PeriodicResetCountMin4 sketch;
	private long sample;
	private int K;
	
	public Indicator(Config config) {
		super();
		Config myConfig = ConfigFactory.parseString("maximum-size = 5000");
		myConfig = myConfig.withFallback(config);
		this.hinter = new Hinter();
		this.estSkew = new EstSkew();
		this.sketch = new PeriodicResetCountMin4(myConfig);
		this.sample = 0;
	    BasicSettings settings = new BasicSettings(myConfig);
	    this.K = settings.tinyLfu().countMin4().K();
	    
	    
	}

	public void record(long key) {
	    int hint = sketch.frequency(key);
		hinter.increment(hint);
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
		return estSkew.estSkew(K);
	}
	
	public double getHint() {
		return hinter.getAverage();
	}
	
	public double getIndicator() {
		double skew = getSkew();
		return (getHint())*(skew < 1 ? 1 - Math.pow(skew, 3) : 0)/15.0;
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
			return ((double) sum) / ((double) count);
		}
		
		public int getSum() {
			return sum;
		}
		
		public int getCount() {
			return count;
		}
		
		public int getMaximal() {
			return freq[1];
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

		public int[] getFreq() {
			return freq.values().stream().sorted().mapToInt(i->i).toArray();
		}
	}

	public long getGini() {
		int[] a = estSkew.getFreq();
		double n = a.length;
		double ginisum = 0;
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			ginisum += 2.0 * (i+1) * a[i];
			sum += a[i];
			
		}
		ginisum = (ginisum / (sum*n)) - (n+1) / n; 
		return (long) (ginisum*1000);
	}

	public long getEntropy() {
		int[] a = estSkew.getFreq();
		double entsum = 0;
		double sum = IntStream.of(a).sum();
		for (int i = 0; i < a.length; i++) {
			double x = a[i]/sum;
			entsum += (x) * Math.log(x);
		}
		return (long) (-entsum*1000);
	}
	
	public long getMaximal() {
		return hinter.getMaximal();
	}

}
