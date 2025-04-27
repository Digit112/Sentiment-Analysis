package com.evelynsalie;

import java.lang.IllegalArgumentException;

public class ModelTestResults {
	private final int num_buckets;
	
	private StatisticsTracker generateds;
	private StatisticsTracker actuals;
	private StatisticsTracker errors;
	
	private int num_false_positive_labels;
	private int num_false_negative_labels;
	private int num_true_positive_labels;
	private int num_true_negative_labels;
	
	public ModelTestResults(int num_buckets) {
		this.num_buckets = num_buckets;
		
		this.generateds = new StatisticsTracker(num_buckets, -2, 2);
		this.actuals = new StatisticsTracker(num_buckets, -2, 2);
		this.errors = new StatisticsTracker(num_buckets, 0, 4);
		
		this.num_false_positive_labels = 0;
		this.num_false_negative_labels = 0;
		this.num_true_positive_labels = 0;
		this.num_true_negative_labels = 0;
	}
	
	/**
	* Adds a pair of labels to the tracked data, one from the training data (actual) and one from the .
	* @param generated The label geenrated by the model.
	* @param actual The label retrieved from the training data.
	*/
	protected void addResult(double generated, double actual) {
		double error = Math.abs(actual - generated);
		
		if (generated < 0) {
			if (actual >= 0) num_false_negative_labels++;
			else if (actual < 0) num_true_negative_labels++;
		}
		else if (generated > 0) {
			if (actual <= 0) num_false_positive_labels++;
			else  if (actual > 0) num_true_positive_labels++;
		}
		
		generateds.addValue(generated);
		actuals.addValue(actual);
		errors.addValue(error);
	}
	
	// Combines the passed result set with this one, overwritting the values on this.
	protected void integrateNewResults(ModelTestResults other) {
		if (this.num_buckets != other.num_buckets) {
			throw new IllegalArgumentException();
		}
		
		this.generateds.integrateNewValues(other.generateds);
		this.actuals.integrateNewValues(other.actuals);
		this.errors.integrateNewValues(other.errors);
		
		this.num_false_positive_labels += other.num_false_positive_labels;
		this.num_false_negative_labels += other.num_false_negative_labels;
		this.num_true_positive_labels += other.num_true_positive_labels;
		this.num_true_negative_labels += other.num_true_negative_labels;
	}
	
	public StatisticsTracker getResultStats() {
		return generateds;
	}
	
	public StatisticsTracker getLabelStats() {
		return actuals;
	}
	
	public StatisticsTracker getErrorStats() {
		return errors;
	}
	
	public String toString() {
		String ret = "            |  Mean | Std Dev |   Min |    Q1 | Median |    Q3 |   Max |\n";
		
		ret += String.format(
			"Act. Labels | %+5.2f |   %+5.2f | %+5.2f | %+5.2f |  %+5.2f | %+5.2f | %+5.2f |\n",
			actuals.getMean(), actuals.getStdDev(), actuals.getMin(), actuals.getQ1(), actuals.getQ2(), actuals.getQ3(), actuals.getMax()
		);
		
		ret += String.format(
			"Gen. Labels | %+5.2f |   %+5.2f | %+5.2f | %+5.2f |  %+5.2f | %+5.2f | %+5.2f |\n",
			generateds.getMean(), generateds.getStdDev(), generateds.getMin(), generateds.getQ1(), generateds.getQ2(), generateds.getQ3(), generateds.getMax()
		);
		
		ret += String.format(
			"     Errors | %+5.2f |   %+5.2f | %+5.2f | %+5.2f |  %+5.2f | %+5.2f | %+5.2f |\n",
			errors.getMean(), errors.getStdDev(), errors.getMin(), errors.getQ1(), errors.getQ2(), errors.getQ3(), errors.getMax()
		);
		
		int total_positive = num_true_positive_labels + num_false_positive_labels;
		int total_negative = num_true_negative_labels + num_false_negative_labels;
		
		ret += String.format("Correctly Labelled Positive: %d / %d (%.2f%%)\n",
			num_true_positive_labels, total_positive, (double) num_true_positive_labels / total_positive * 100
		);
		
		ret += String.format("Correctly Labelled Negative: %d / %d (%.2f%%)\n",
			num_true_negative_labels, total_negative, (double) num_true_negative_labels / total_negative * 100
		);
		
		return ret;
	}
}