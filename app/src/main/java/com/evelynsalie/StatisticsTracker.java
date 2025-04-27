package com.evelynsalie;

// A simple utility class for tracking the statistics of a stream of numbers.
public class StatisticsTracker {
	private final int num_buckets;
	private int[] values_buckets;
	private final double min_possible_value;
	private final double max_possible_value;
	
	private double min;
	private double max;
	
	private int num_values;
	private double values_sum;
	private double values_sqr_sum;
	
	/**
	* @param num_buckets The number of buckets to use in a histogram of the values. If 0, buckets will not be used and some functions will throw an error when called.
	* @param min_possible_value The minimum value that can be received in by this object. Used when calculating bucket size and values.
	* @param max_possible_value The maximum value that can be received in by this object. Used when calculating bucket size and values.
	*/
	public StatisticsTracker(int num_buckets, double min_possible_value, double max_possible_value) {
		if (max_possible_value < min_possible_value) {
			throw new IllegalArgumentException("min_possible_value must be less than or equal to max_possible_value.");
		}
		
		if (num_buckets < 0) {
			throw new IllegalArgumentException("num_buckets must be non-negative.");
		}
		
		this.num_buckets = num_buckets;
		if (num_buckets > 0) this.values_buckets = new int[num_buckets];
		this.min_possible_value = min_possible_value;
		this.max_possible_value = max_possible_value;
		
		this.min = max_possible_value;
		this.max = min_possible_value;
		
		this.num_values = 0;
		this.values_sum = 0;
		this.values_sqr_sum = 0;
	}
	
	protected void addValue(double value) {
		if (value < min_possible_value || value > max_possible_value) {
			throw new IllegalArgumentException(
				"Argument " + value + " is not in the specified range " + min_possible_value + " - " + max_possible_value
			);
		}
		
		if (Double.isNaN(value)) {
			throw new IllegalArgumentException("Argument must not be NaN.");
		}
		
		if (value < min) {
			min = value;
		}
		if (value > max) {
			max = value;
		}
		
		num_values++;
		values_sum += value;
		values_sqr_sum += value*value;
		
		assert !Double.isNaN(values_sum);
		assert !Double.isNaN(values_sqr_sum);
		
		if (num_buckets > 0) {
			// Get bucket index. Will evaluate to num_buckets if value == max_possible_value.
			// The min() prevents this from causing an invalid access.
			int bucket_index = (int) ((value - min_possible_value) / (max_possible_value - min_possible_value) * num_buckets);
			values_buckets[Math.min(bucket_index, num_buckets-1)]++;
		}
	}
	
	// Combines the passed result set with this one, overwritting the values on this.
	protected void integrateNewValues(StatisticsTracker other) {
		if (other.num_buckets != this.num_buckets) {
			throw new IllegalArgumentException("Cannot integrate results from a statistics tracker with a different number of buckets.");
		}
		
		if (other.min_possible_value != this.min_possible_value || other.max_possible_value != this.max_possible_value) {
			throw new IllegalArgumentException("Cannot integrate results from a statistics tracker with a different upper or lower bound.");
		}
		
		if (other.min < this.min) this.min = other.min;
		if (other.max > this.max) this.max = other.max;
		
		this.num_values += other.num_values;
		this.values_sum += other.values_sum;
		this.values_sqr_sum += other.values_sqr_sum;
		
		assert !Double.isNaN(values_sum);
		assert !Double.isNaN(values_sqr_sum);
		
		if (num_buckets > 0) {
			for (int i = 0; i < num_buckets; i++) {
				this.values_buckets[i] += other.values_buckets[i];
			}
		}
	}
	
	/**
	* Get the number of values added to this tracker.
	*/
	public int getCount() {
		return num_values;
	}
	
	/**
	* Get the mean of all values passed to addValue()
	*/
	public double getMean() {
		if (num_values == 0) throw new IllegalStateException();
		return values_sum / num_values;
	}
	
	/**
	* Get the standard deviation of all values passed to addValue()
	*/
	public double getStdDev() {
		if (num_values == 0) throw new IllegalStateException();
		return Math.sqrt(values_sqr_sum / num_values - getMean()*getMean());
	}
	
	/**
	* Get the minimum of all values passed to addValue()
	*/
	public double getMin() {
		if (num_values == 0) throw new IllegalStateException();
		return min;
	}
	
	/**
	* Get the maximum of all values passed to addValue()
	*/
	public double getMax() {
		if (num_values == 0) throw new IllegalStateException();
		return max;
	}
	
	/**
	* Get an approximation of the values at the given percentiles.
	* The returned values are not necessarily actual numbers that were previously passed
	* to addValue(), they are based on the counts of items in the buckets.
	* Faster than calling getPercentile() repeatedly.
	* @param percents A sorted, ascending list of percent values, ranging from 0 to 100, to be retrieved.
	* @throws IllegalStateException if buckets are not in use, or no value has been passed to this function.
	*/
	public double[] getPercentiles(double[] percents) {
		if (num_values == 0) throw new IllegalStateException();
		if (num_buckets == 0) throw new IllegalStateException();
		if (percents[0] < 0 || percents[0] > 100) throw new IllegalArgumentException();
		
		double[] percentiles = new double[percents.length];
		
		int sum = 0;
		int next_i = 0;
		for (int i = 0; i < num_buckets; i++) {
			sum += values_buckets[i];
			if (sum >= num_values * percents[next_i]) {
				percentiles[next_i] = lerp(min_possible_value, max_possible_value, (i + 0.5) / num_buckets);
				
				next_i++;
				if (i == percents.length) return percentiles;
				if (percents[next_i] < 0 || percents[next_i] > 100) throw new IllegalArgumentException();
				if (percents[next_i] < percents[next_i-1]) throw new IllegalArgumentException();
			}
		}
		
		assert false;
		return new double[0];
	}
	
	/**
	* Get an approximation of the value at the given percentile.
	* The returned value is not necessarily an actual value that was previously passed
	* to addValue(), it is based on the counts of items in the buckets.
	* @param percents The percentile, ranging from 0 to 100, to be retrieved.
	* @throws IllegalStateException if buckets are not in use, or no value has been passed to this function.
	*/
	public double getPercentile(double percent) {
		if (num_values == 0) throw new IllegalStateException();
		if (num_buckets == 0) throw new IllegalStateException();
		if (percent < 0 || percent > 100) throw new IllegalArgumentException();
		
		int sum = 0;
		for (int i = 0; i < num_buckets; i++) {
			sum += values_buckets[i];
			if (sum >= num_values * percent / 100) {
				return lerp(min_possible_value, max_possible_value, (i + 0.5) / num_buckets);
			}
		}
		
		assert false;
		return Float.NaN;
	}
	
	/**
	* Shorthand for getPercentile(25)
	*/
	public double getQ1() {
		return getPercentile(25);
	}
	
	/**
	* Shorthand for getPercentile(50)
	*/
	public double getQ2() {
		return getPercentile(50);
	}
	
	/**
	* Shorthand for getPercentile(75)
	*/
	public double getQ3() {
		return getPercentile(75);
	}
	
	/**
	* Prints out a sideways ASCII histogram with each bar representing a group of buckets.
	* @param num_bars The number of bars to draw and, consequently, the level of detail in the histogram. Must evenly divide num_buckets.
	*/
	public void printHistogram(int num_bars) {
		if (num_values == 0) throw new IllegalStateException();
		if (num_buckets == 0) throw new IllegalStateException();
		if (num_buckets % num_bars != 0) throw new IllegalArgumentException("num_bars must evenly divide num_buckets.");
		
		int buckets_per_bar = num_buckets / num_bars;
		int counts_per_dash = num_values / 10 / num_bars; // Causes each bar to have, on average, 10 dashes.
		
		for (int i = 0; i < num_bars; i++) {
			// Sum multiple buckets together.
			int total_counts = 0;
			for (int j = 0; j < buckets_per_bar; j++) total_counts += values_buckets[i * buckets_per_bar + j];
			
			// Print labels
			if (i == 0) System.out.print(String.format("%+5.2f ", min_possible_value));
			else if (i == num_bars-1) System.out.print(String.format("%+5.2f ", max_possible_value));
			else if (i % 2 == 0) System.out.print(String.format("%+5.2f ", lerp(min_possible_value, max_possible_value, (double) i / (num_bars - 1))));
			else System.out.print("      ");
			
			// Print dashes
			int num_dashes = total_counts / counts_per_dash; // Note rounding down.
			for (int j = 0; j < num_dashes; j++) System.out.print("-");
			
			System.out.print("\n");
		}
	}
	
	private double lerp(double a, double b, double t) {
		return (b - a)*t + a;
	}
}