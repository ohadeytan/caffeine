/**
 * 
 */
package com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.JAXBException;

import org.dmg.pmml.FieldName;
import org.dmg.pmml.PMML;
import org.jpmml.evaluator.Computable;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.evaluator.InputField;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.xml.sax.SAXException;

import com.github.benmanes.caffeine.cache.simulator.policy.sketch.Indicator;
import com.github.benmanes.caffeine.cache.simulator.policy.sketch.climbing.HillClimberWindowTinyLfuPolicy.HillClimberWindowTinyLfuSettings;
import com.typesafe.config.Config;

/**
 * @author ohad
 *
 */
public final class MLTreeClimber implements HillClimber {

	private final Indicator indicator;
	private double prevPercent;
	private int cacheSize;
	private Evaluator evaluator;
	Map<String, Float> features_map;
	
	public MLTreeClimber(Config config) {
	    HillClimberWindowTinyLfuSettings settings = new HillClimberWindowTinyLfuSettings(config);
		this.prevPercent = 1 - settings.percentMain().get(0);
		this.cacheSize = settings.maximumSize();
		this.indicator = new Indicator(config);

		InputStream is;
		PMML pmml = null;
		try {
			is = new FileInputStream(settings.model());
			pmml = org.jpmml.model.PMMLUtil.unmarshal(is);
		} catch (SAXException | JAXBException | FileNotFoundException e) {
			e.printStackTrace();
		}
		ModelEvaluatorFactory modelEvaluatorFactory = ModelEvaluatorFactory.newInstance();
		this.evaluator = (Evaluator)modelEvaluatorFactory.newModelEvaluator(pmml);
		evaluator.verify();
		features_map = new LinkedHashMap<>();
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
			double oldPercent = prevPercent;
			
			int[] freqs = indicator.getFreqs();
			for (int i = 0; i < 16; i++) {
				String fn = "freq-" + Integer.toString(i);
				features_map.put(fn, (float) freqs[i]);
			}
			features_map.put("Hint", (float) indicator.getHint());
			features_map.put("Skew", (float) indicator.getSkew());
			features_map.put("Gini", (float) indicator.getGini());
			features_map.put("Entropy", (float) indicator.getEntropy());
			features_map.put("Uniques", (float) indicator.getUniques());
			features_map.put("CacheSize", (float) cacheSize);
			
			Map<FieldName, FieldValue> arguments = new LinkedHashMap<>();
			List<InputField> inputFields = evaluator.getInputFields();
			for(InputField inputField : inputFields){
				FieldName inputFieldName = inputField.getName();
				Object rawValue = features_map.get(inputFieldName.getValue());
				FieldValue inputFieldValue = inputField.prepare(rawValue);
				arguments.put(inputFieldName, inputFieldValue);
//				System.out.println(inputFieldName);
//				System.out.println(inputFieldValue);
			}
			Object targetValue = evaluator.evaluate(arguments).get(new FieldName("Target"));
			
			double newPercent;
			if (targetValue instanceof Computable) {
				newPercent = (double) ((Computable) targetValue).getResult() / 100.0;
			} else {
				newPercent = (double) targetValue / 100.0;
			}
            newPercent = newPercent < 0 ? 0 : newPercent;
            newPercent = newPercent > 0.8 ? 0.8 : newPercent;
            prevPercent = newPercent;
			System.out.println(newPercent);
//			System.out.println("============!");
			
			indicator.reset();
			if (newPercent > oldPercent) {
				return new Adaptation(Adaptation.Type.INCREASE_WINDOW, (int)((newPercent - oldPercent)*cacheSize));
			}
			return new Adaptation(Adaptation.Type.DECREASE_WINDOW, (int)((oldPercent - newPercent)*cacheSize));
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
}
