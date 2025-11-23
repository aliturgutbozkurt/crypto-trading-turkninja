package com.turkninja.infra.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.turkninja.infra.DatabaseService;
import org.bson.Document;

public class TradeRepository {
    private final MongoCollection<Document> collection;

    public TradeRepository(DatabaseService databaseService) {
        if (databaseService != null) {
            this.collection = databaseService.getDatabase().getCollection("trades");
        } else {
            this.collection = null;
        }
    }

    public void saveTrade(Document trade) {
        if (collection != null) {
            collection.insertOne(trade);
        }
    }

    public void updateTrade(String symbol, double exitPrice, double pnl, String status) {
        if (collection != null) {
            try {
                // Update the most recent OPEN trade for this symbol
                Document filter = new Document("symbol", symbol)
                        .append("status", "OPEN");

                Document update = new Document("$set", new Document()
                        .append("exitPrice", exitPrice)
                        .append("pnl", pnl)
                        .append("status", status)
                        .append("closeTime", java.time.Instant.now()));

                // Update only the latest one
                collection.updateOne(filter, update);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public java.util.List<com.turkninja.model.Trade> findRecentClosedTrades(int limit) {
        java.util.List<com.turkninja.model.Trade> trades = new java.util.ArrayList<>();
        if (collection == null)
            return trades;

        try {
            // Find trades where closeTime exists (meaning it's closed)
            // Sort by closeTime descending
            collection.find(new Document("closeTime", new Document("$exists", true)))
                    .sort(new Document("closeTime", -1))
                    .limit(limit)
                    .forEach(doc -> {
                        com.turkninja.model.Trade trade = new com.turkninja.model.Trade();
                        trade.setSymbol(doc.getString("symbol"));
                        trade.setSide(doc.getString("side"));
                        trade.setEntryPrice(doc.getDouble("entryPrice"));
                        trade.setExitPrice(doc.getDouble("exitPrice") != null ? doc.getDouble("exitPrice") : 0.0);
                        trade.setQuantity(doc.getDouble("quantity"));
                        trade.setPnl(doc.getDouble("pnl") != null ? doc.getDouble("pnl") : 0.0);
                        trade.setTimestamp(doc.getDate("timestamp"));
                        trade.setCloseTime(doc.getDate("closeTime"));
                        trade.setStatus(doc.getString("status"));
                        trades.add(trade);
                    });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return trades;
    }
}
