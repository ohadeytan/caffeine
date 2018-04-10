/**
 * 
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import java.util.ArrayList;
import java.util.List;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.Config;

/**
 * @author ohad
 */
public final class ExtractFeaturesClimber implements HillClimber {

	private final Indicator indicator;
	private int cacheSize;
	private Features features;
	private boolean firstPeriod;
	
	public ExtractFeaturesClimber(Config config) {
	    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
		this.cacheSize = settings.maximumSize();
		this.indicator = new Indicator(config);
		this.features = new Features();
		this.features.setCachesize(cacheSize);
		this.firstPeriod = true;
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
		if (indicator.getSample() == 50000) {
			if (firstPeriod) { // For warm up
				firstPeriod = false;
				indicator.reset();
				return new Adaptation(Adaptation.Type.HOLD, 0);
			}
			features.incMultiplier();
			features.addFreq(indicator.getFreqs());
			features.addHint(indicator.getHint());
			features.addSkew(indicator.getSkew());
			features.addGini(indicator.getGini());
			features.addEntropy(indicator.getEntropy());
			features.addUniques(indicator.getUniques());
			indicator.reset();
		}
		return new Adaptation(Adaptation.Type.HOLD, 0);
	}

	public class Features {
		int multiplier = 0;
		int[] freq = new int[16];
		double hint;
		double skew;
		double gini;
		double entropy;
		int uniques;
		int cachesize;
		public Features() {
			for (int i = 0; i < freq.length; i++) {
				freq[i] = 0;
			}
		}

		public void incMultiplier() {
			this.multiplier++;
		}
		public void addFreq(int[] freq) {
			for (int i = 0; i < freq.length; i++) {
				this.freq[i] += freq[i];				
			}
		}
		public void addHint(double hint) {
			this.hint += hint;
		}
		public void addSkew(double skew) {
			this.skew += skew;
		}
		public void addGini(double gini) {
			this.gini += gini;
		}
		public void addEntropy(double entropy) {
			this.entropy += entropy;
		}
		public void addUniques(int uniques) {
			this.uniques += uniques;
		}
		public void setCachesize(int cachesize) {
			this.cachesize = cachesize;
		}
		public List<Double> getFeatures() {
			List<Double> features = new ArrayList<Double>();
			features.add((double) multiplier);
			
			for (int i = 0; i < freq.length; i++) {
				features.add((double) freq[i]);
			}
			features.add(hint);
			features.add(skew);
			features.add(gini);
			features.add(entropy);
			features.add((double) uniques);
			features.add((double) cachesize);
			return features;
		}		
		
	}

	public List<Double> getExtraInfo() {
		return features.getFeatures();
	}
}
