package com.turkninja.model.optimizer;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a single set of strategy parameters
 */
public class ParameterSet {

    private final Map<String, Double> parameters;

    public ParameterSet() {
        this.parameters = new HashMap<>();
    }

    public ParameterSet(Map<String, Double> parameters) {
        this.parameters = new HashMap<>(parameters);
    }

    public void set(String name, double value) {
        parameters.put(name, value);
    }

    public double get(String name) {
        return parameters.getOrDefault(name, 0.0);
    }

    public double get(String name, double defaultValue) {
        return parameters.getOrDefault(name, defaultValue);
    }

    public boolean has(String name) {
        return parameters.containsKey(name);
    }

    public Map<String, Double> getAll() {
        return new HashMap<>(parameters);
    }

    public int size() {
        return parameters.size();
    }

    public ParameterSet copy() {
        return new ParameterSet(this.parameters);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        ParameterSet that = (ParameterSet) o;
        return Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameters);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ParameterSet{");
        parameters.forEach((k, v) -> sb.append(k).append("=").append(String.format("%.2f", v)).append(", "));
        if (sb.length() > 13) {
            sb.setLength(sb.length() - 2); // Remove last ", "
        }
        sb.append("}");
        return sb.toString();
    }
}
