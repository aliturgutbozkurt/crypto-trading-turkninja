#!/bin/bash

# Quick Backtest Script
# Runs backtest via the running bot's system (reuses compiled classes)

echo "🚀 Starting Quick Backtest..."
echo "Note: Make sure the bot is running (./start.sh)"
echo ""

# Use the running Maven process
cd /Users/aliturgutbozkurt/Desktop/crypto-trading/crypto-trading-turkninja

# Run via Maven (reuses dependencies)
mvn -q compile && \
echo "✅ Compiled successfully" && \
mvn -q exec:java \
  -Dexec.mainClass="com.turkninja.QuickBacktestRunner" \
  -Dexec.cleanupDaemonThreads=false \
  -Dexec.classpathScope=runtime | tee backtest_output.log

echo ""
echo "✅ Backtest complete! Results saved to backtest_results.txt"
