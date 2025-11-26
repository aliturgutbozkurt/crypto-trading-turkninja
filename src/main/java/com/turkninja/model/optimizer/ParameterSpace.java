package com.turkninja.model.optimizer;

import java.util.*;

/**
 * Defines the search space for parameter optimization
 * Contains multiple ParameterRanges and generates combinations
 */
public class ParameterSpace {

    private final Map<String, ParameterRange> ranges;

    public ParameterSpace() {
        this.ranges = new LinkedHashMap<>();
    }

    /**
     * Add a continuous parameter range
     */
    public ParameterSpace addRange(String name, double min, double max, double step) {
        ranges.put(name, new ParameterRange(name, min, max, step));
        return this;
    }

    /**
     * Add a discrete parameter range
     */
    public ParameterSpace addDiscrete(String name, List<Double> values) {
        ranges.put(name, new ParameterRange(name, values));
        return this;
    }

    /**
     * Add a discrete parameter range from varargs
     */
    public ParameterSpace addDiscrete(String name, double... values) {
        List<Double> valueList = new ArrayList<>();
        for (double v : values) {
            valueList.add(v);
        }
        return addDiscrete(name, valueList);
    }

    /**
     * Get all possible parameter combinations (for Grid Search)
     * WARNING: Can be very large! Check getTotalCombinations() first
     */
    public List<ParameterSet> getAllCombinations() {
        List<ParameterSet> combinations = new ArrayList<>();

        if (ranges.isEmpty()) {
            return combinations;
        }

        // Get parameter names and values list
        List<String> paramNames = new ArrayList<>(ranges.keySet());
        List<List<Double>> allValues = new ArrayList<>();

        for (String name : paramNames) {
            allValues.add(ranges.get(name).getValues());
        }

        // Generate all combinations using recursive approach
        generateCombinations(combinations, new ParameterSet(), paramNames, allValues, 0);

        return combinations;
    }

    private void generateCombinations(List<ParameterSet> result, ParameterSet current,
            List<String> paramNames, List<List<Double>> allValues, int depth) {
        if (depth == paramNames.size()) {
            result.add(current.copy());
            return;
        }

        String paramName = paramNames.get(depth);
        List<Double> values = allValues.get(depth);

        for (double value : values) {
            current.set(paramName, value);
            generateCombinations(result, current, paramNames, allValues, depth + 1);
        }
    }

    /**
     * Get a random parameter set (for Genetic Algorithm initialization)
     */
    public ParameterSet randomSample() {
        ParameterSet params = new ParameterSet();
        for (ParameterRange range : ranges.values()) {
            params.set(range.getName(), range.getRandomValue());
        }
        return params;
    }

    /**
     * Calculate total number of combinations
     */
    public long getTotalCombinations() {
        long total = 1;
        for (ParameterRange range : ranges.values()) {
            total *= range.getValueCount();
        }
        return total;
    }

    /**
     * Get parameter range by name
     */
    public ParameterRange getRange(String name) {
        return ranges.get(name);
    }

    /**
     * Check if parameter exists
     */
    public boolean hasParameter(String name) {
        return ranges.containsKey(name);
    }

    /**
     * Get all parameter names
     */
    public Set<String> getParameterNames() {
        return new HashSet<>(ranges.keySet());
    }

    /**
     * Get number of parameters
     */
    public int getParameterCount() {
        return ranges.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ParameterSpace{\n");
        ranges.forEach((name, range) -> sb.append("  ").append(range).append("\n"));
        sb.append("  Total combinations: ").append(getTotalCombinations()).append("\n");
        sb.append("}");
        return sb.toString();
    }
}
