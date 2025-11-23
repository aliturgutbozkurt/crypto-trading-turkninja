package com.turkninja.engine;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

public class TradeAnalyzer {
    public static void main(String[] args) {
        String uri = "mongodb://localhost:27017";
        try (MongoClient mongoClient = MongoClients.create(uri)) {
            MongoDatabase database = mongoClient.getDatabase("crypto_trading");
            MongoCollection<Document> collection = database.getCollection("trades");

            System.out.println("--- Trade Analysis (Last 50 Trades) ---");

            List<Document> trades = collection.find(new Document("closeTime", new Document("$exists", true)))
                    .sort(new Document("closeTime", -1))
                    .limit(50)
                    .into(new ArrayList<>());

            double totalPnl = 0;
            int wins = 0;
            int losses = 0;
            double totalCommissionEst = 0;

            for (Document trade : trades) {
                String symbol = trade.getString("symbol");
                String side = trade.getString("side");
                double entry = trade.getDouble("entryPrice") != null ? trade.getDouble("entryPrice") : 0.0;
                double exit = trade.getDouble("exitPrice") != null ? trade.getDouble("exitPrice") : 0.0;
                double qty = trade.getDouble("quantity") != null ? trade.getDouble("quantity") : 0.0;
                double pnl = trade.getDouble("pnl") != null ? trade.getDouble("pnl") : 0.0;
                String status = trade.getString("status");

                if (entry == 0 || qty == 0) {
                    System.out.printf("%s %s | SKIPPED (Missing Data)%n", symbol, side);
                    continue;
                }

                double notional = entry * qty;
                double commission = notional * 0.001; // Est 0.1% round trip
                double netPnl = pnl - commission;

                totalPnl += pnl;
                totalCommissionEst += commission;

                if (pnl > 0)
                    wins++;
                else
                    losses++;

                System.out.printf("%s %s | PnL: $%.2f | Comm: ~$%.2f | Net: $%.2f | %s%n",
                        symbol, side, pnl, commission, netPnl, status);
            }

            System.out.println("\n--- Summary ---");
            System.out.printf("Total Trades: %d%n", trades.size());
            System.out.printf("Wins: %d | Losses: %d | Win Rate: %.1f%%%n", wins, losses,
                    (double) wins / trades.size() * 100);
            System.out.printf("Gross PnL: $%.2f%n", totalPnl);
            System.out.printf("Est. Commission: $%.2f%n", totalCommissionEst);
            System.out.printf("Net PnL: $%.2f%n", totalPnl - totalCommissionEst);
        }
    }
}
