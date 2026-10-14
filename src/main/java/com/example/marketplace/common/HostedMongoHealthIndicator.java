package com.example.marketplace.common;

import org.bson.Document;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/** Checks the database the application uses, without querying Atlas internal databases. */
@Component("mongoHealthIndicator")
@Profile("production")
public class HostedMongoHealthIndicator extends AbstractHealthIndicator {

    private final MongoTemplate mongo;

    public HostedMongoHealthIndicator(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Document result = mongo.executeCommand(new Document("ping", 1));
        Object ok = result.get("ok");
        if (ok instanceof Number value && value.doubleValue() == 1.0) {
            builder.up();
        } else {
            builder.down();
        }
    }
}
