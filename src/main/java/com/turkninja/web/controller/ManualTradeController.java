package com.turkninja.web.controller;

import com.turkninja.engine.StrategyEngine;
import com.turkninja.infra.FuturesBinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ManualTradeController {

    private static final Logger logger = LoggerFactory.getLogger(ManualTradeController.class);

    @Autowired
    private FuturesBinanceService futuresService;

    @Autowired
    private StrategyEngine strategyEngine;

    @PostMapping("/manual-trade")
    public ResponseEntity<?> openManualTrade(@RequestBody Map<String, Object> request) {
        try {
            String symbol = (String) request.get("symbol");
            String side = (String) request.get("side");
            Double quantity = request.get("quantity") != null
                    ? ((Number) request.get("quantity")).doubleValue()
                    : null;
            Integer balancePercent = request.get("balancePercent") != null
                    ? ((Number) request.get("balancePercent")).intValue()
                    : 25; // Default 25%

            // Validation
            if (symbol == null || symbol.isEmpty()) {
                return ResponseEntity.badRequest().body("Symbol is required");
            }
            if (side == null || (!side.equals("BUY") && !side.equals("SELL"))) {
                return ResponseEntity.badRequest().body("Side must be BUY or SELL");
            }

            logger.info("📝 Manual trade request: {} {} ({}% kalan bakiye)", side, symbol, balancePercent);

            // Get current price
            double currentPrice = futuresService.getSymbolPriceTicker(symbol);

            // Calculate quantity if not provided - USE AVAILABLE BALANCE (not total)
            if (quantity == null) {
                // Get AVAILABLE balance (not total balance)
                double availableBalance = futuresService.getAvailableBalance();
                logger.info("💰 Available balance: ${}", availableBalance);

                // Use percentage of AVAILABLE balance
                double positionSize = availableBalance * (balancePercent / 100.0);
                quantity = (positionSize * 20) / currentPrice; // 20x leverage

                // Round to appropriate precision
                int precision = futuresService.getQuantityPrecision(symbol);
                double scale = Math.pow(10, precision);
                quantity = Math.floor(quantity * scale) / scale;

                logger.info("💰 Using {}% of available balance: ${} → Position size: ${} → Qty: {}",
                        balancePercent, availableBalance, positionSize, quantity);
            }

            logger.info("💰 Opening manual {} position: {} @ ${} (qty: {}, {}% kalan bakiye)",
                    side, symbol, currentPrice, quantity, balancePercent);

            // Place order
            String orderId = futuresService.placeMarketOrder(symbol, side, quantity);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("orderId", orderId);
            response.put("symbol", symbol);
            response.put("side", side);
            response.put("quantity", quantity);
            response.put("price", currentPrice);
            response.put("balancePercent", balancePercent);
            response.put("message", String.format("✅ Manuel %s pozisyonu açıldı: %s @ $%.2f (%d%% kalan bakiye)",
                    side, symbol, currentPrice, balancePercent));

            logger.info("✅ Manual trade executed: {} {} @ ${}", side, symbol, currentPrice);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("❌ Manual trade failed", e);
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    @GetMapping("/trading-symbols")
    public ResponseEntity<?> getTradingSymbols() {
        try {
            String[] symbols = {
                    "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "DOGEUSDT",
                    "XRPUSDT", "MATICUSDT", "LTCUSDT", "ETCUSDT", "LUNA2USDT",
                    "ASTERUSDT", "TAOUSDT"
            };
            return ResponseEntity.ok(symbols);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        }
    }

    // Add position to tracker after successful order
    private void trackPosition(String symbol, String side, double price, double quantity) {
        try {
            // This will be called after order is placed
            logger.info("📊 Adding manual position to tracker: {} {} @ ${}", side, symbol, price);
            // Position will be picked up by next reload from Binance
        } catch (Exception e) {
            logger.error("Error tracking manual position", e);
        }
    }
}
