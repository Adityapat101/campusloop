package com.example.marketplace.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.mongodb.core.MongoTemplate;

@ExtendWith(MockitoExtension.class)
class HostedMongoHealthIndicatorTests {

    @Mock MongoTemplate mongo;
    private HostedMongoHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new HostedMongoHealthIndicator(mongo);
    }

    @Test
    void checksOnlyTheApplicationDatabase() {
        when(mongo.executeCommand(new Document("ping", 1))).thenReturn(new Document("ok", 1.0));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        verify(mongo).executeCommand(new Document("ping", 1));
    }

    @Test
    void reportsDownWhenMongoCannotBeReached() {
        when(mongo.executeCommand(new Document("ping", 1)))
                .thenThrow(new IllegalStateException("Database unavailable"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenMongoRejectsThePing() {
        when(mongo.executeCommand(new Document("ping", 1))).thenReturn(new Document("ok", 0.0));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
