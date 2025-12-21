#!/bin/bash

# TurkNinja Backtest Runner
# Usage: ./run-backtest.sh BTCUSDT 2024-01-01 2024-06-01 15m

if [ "$#" -lt 4 ]; then
    echo "Usage: ./run-backtest.sh <symbol> <startDate> <endDate> <timeframe>"
    echo "Example: ./run-backtest.sh BTCUSDT 2024-01-01 2024-06-01 15m"
    exit 1
fi

SYMBOL=$1
START_DATE=$2
END_DATE=$3
TIMEFRAME=$4

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# Load environment
if [ -f ".env" ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# Compile if needed
echo "🔨 Compiling..."
mvn compile -DskipTests -q

if [ $? -ne 0 ]; then
    echo "❌ Compilation failed"
    exit 1
fi

# Run backtest
echo "🚀 Running backtest..."
mvn exec:java -Dexec.mainClass="com.turkninja.BacktestCLI" \
    -Dexec.args="$SYMBOL $START_DATE $END_DATE $TIMEFRAME" \
    -q

echo ""
echo "📁 Reports saved in: backtest_reports/"
