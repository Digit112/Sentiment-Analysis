package com.evelynsalie;

public class StatisticsTrackerDTO {
	private int count;
	private double mean;
	private double stddev;
	
	private double min;
	private double qt1;
	private double med;
	private double qt3;
	private double max;
	
	public StatisticsTrackerDTO(StatisticsTracker other) {
		this.count = other.getCount();
		this.mean = other.getMean();
		this.stddev = other.getStdDev();
		
		this.min = other.getMin();
		this.qt1 = other.getQ1();
		this.med = other.getQ2();
		this.qt3 = other.getQ3();
		this.max = other.getMax();
	}
	
	public String getJSON() {
		return String.format(
			"{\"count\":%d, \"mean\":%.2f, \"stddev\":%.2f, \"min\":%.2f, \"qt1\":%.2f, \"med\":%.2f, \"qt3\":%.2f, \"max\":%.2f}",
			count, mean, stddev, min, qt1, med, qt3, max
		);
	}
}