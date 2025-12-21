#!/bin/bash

# Configuration
SYMBOLS=("BTCUSDT" "ETHUSDT" "SOLUSDT" "AVAXUSDT" "DOGEUSDT" "XRPUSDT" "MATICUSDT" "LTCUSDT" "ETCUSDT" "TAOUSDT")
START_DATE="2024-06-01"
END_DATE="2024-12-01"
TIMEFRAME="15m"

# Create reports directory
mkdir -p backtest_reports

echo "🚀 Starting Multi-Coin Backtest"
echo "Period: $START_DATE to $END_DATE ($TIMEFRAME)"
echo "Symbols: ${SYMBOLS[*]}"
echo "----------------------------------------"

for SYMBOL in "${SYMBOLS[@]}"; do
    echo "▶️  Testing $SYMBOL..."
    ./run-backtest.sh $SYMBOL $START_DATE $END_DATE $TIMEFRAME
    echo "✅ $SYMBOL complete"
    echo "----------------------------------------"
    sleep 2 # Short pause
done

echo "🏁 All backtests completed."
grep "Net Profit" backtest_reports/*${TIMEFRAME}*${START_DATE}*.csv 2>/dev/null
