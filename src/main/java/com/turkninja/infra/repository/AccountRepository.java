package com.turkninja.infra.repository;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.turkninja.infra.DatabaseService;
import org.bson.Document;

public class AccountRepository {
    private final MongoCollection<Document> collection;

    public AccountRepository(DatabaseService databaseService) {
        this.collection = databaseService.getDatabase().getCollection("account_snapshots");
    }

    public void saveSnapshot(Document snapshot) {
        // Assuming snapshot has a timestamp or unique ID we can use as key,
        // or we just insert a new record for history.
        // For now, let's just insert.
        snapshot.append("timestamp", System.currentTimeMillis());
        collection.insertOne(snapshot);
    }

    public Document getLatestSnapshot() {
        return collection.find().sort(new Document("timestamp", -1)).first();
    }
}
