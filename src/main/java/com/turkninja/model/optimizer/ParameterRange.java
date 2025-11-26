package com.turkninja.model.optimizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a range of values for a single parameter
 */
public class ParameterRange {

    private final String name;
    private final double min;
    private final double max;
    private final double step;
    private final List<Double> discreteValues;
    private final boolean isDiscrete;

    /**
     * Create a continuous range (min to max with step)
     */
    public ParameterRange(String name, double min, double max, double step) {
        this.name = name;
        this.min = min;
        this.max = max;
        this.step = step;
        this.discreteValues = null;
        this.isDiscrete = false;

        if (min >= max) {
            throw new IllegalArgumentException("Min must be less than max");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("Step must be positive");
        }
    }

    /**
     * Create a discrete range (specific values only)
     */
    public ParameterRange(String name, List<Double> values) {
        this.name = name;
        this.discreteValues = new ArrayList<>(values);
        this.isDiscrete = true;
        this.min = 0;
        this.max = 0;
        this.step = 0;

        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Discrete values cannot be empty");
        }
    }

    /**
     * Get all possible values in this range
     */
    public List<Double> getValues() {
        if (isDiscrete) {
            return new ArrayList<>(discreteValues);
        }

        List<Double> values = new ArrayList<>();
        for (double val = min; val <= max; val += step) {
            values.add(val);
        }
        return values;
    }

    /**
     * Get a random value from this range
     */
    public double getRandomValue() {
        if (isDiscrete) {
            int idx = (int) (Math.random() * discreteValues.size());
            return discreteValues.get(idx);
        }

        // For continuous: random value between min and max
        return min + Math.random() * (max - min);
    }

    public String getName() {
        return name;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getStep() {
        return step;
    }

    public boolean isDiscrete() {
        return isDiscrete;
    }

    public int getValueCount() {
        return getValues().size();
    }

    @Override
    public String toString() {
        if (isDiscrete) {
            return String.format("%s: %s", name, discreteValues);
        }
        return String.format("%s: [%.2f-%.2f step %.2f]", name, min, max, step);
    }
}
