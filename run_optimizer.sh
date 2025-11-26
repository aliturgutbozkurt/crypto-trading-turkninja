#!/bin/bash

# Compile first
echo "Compiling..."
mvn clean compile

# Run Optimizer
# Usage: ./run_optimizer.sh <method> <symbol> <startDate> <endDate>
# Example: ./run_optimizer.sh grid ETHUSDT 2024-01-01 2024-02-01

METHOD=${1:-grid}
SYMBOL=${2:-ETHUSDT}
START_DATE=${3:-2024-01-01}
END_DATE=${4:-2024-02-01}

echo "Running Optimizer ($METHOD) for $SYMBOL from $START_DATE to $END_DATE..."

mvn exec:java -Dexec.mainClass="com.turkninja.OptimizerRunner" \
    -Dexec.args="$METHOD $SYMBOL $START_DATE $END_DATE"
