package com.example.marketplace.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.marketplace.listing.Listing;
import com.example.marketplace.listing.ListingRepository;
import com.example.marketplace.listing.ListingStatus;
import com.example.marketplace.user.UserAccount;
import com.example.marketplace.user.UserRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class DemoDataInitializerTests {

    @Mock UserRepository users;
    @Mock ListingRepository listings;
    @Mock MongoTemplate mongo;

    private DemoDataInitializer initializer;
    private UserAccount maya;
    private UserAccount noah;

    @BeforeEach
    void setUp() {
        initializer = new DemoDataInitializer(users, listings, mongo);
        maya = mock(UserAccount.class);
        noah = mock(UserAccount.class);
    }

    @Test
    void recoversTheUserCommittedByAnotherInstanceWithoutChangingExistingListings() {
        when(users.findByEmailIgnoreCase("maya@campus.edu"))
                .thenReturn(Optional.empty(), Optional.of(maya));
        when(users.saveAndFlush(any(UserAccount.class)))
                .thenThrow(new DataIntegrityViolationException("concurrent unique email insert"));
        when(users.findByEmailIgnoreCase("noah@campus.edu")).thenReturn(Optional.of(noah));
        when(listings.existsByTitleIgnoreCase(anyString())).thenReturn(true);

        initializer.run();

        verify(users, times(2)).findByEmailIgnoreCase("maya@campus.edu");
        verifyNoInteractions(mongo);
    }

    @Test
    void propagatesAnIntegrityFailureWhenNoConcurrentUserWasCreated() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("database failure");
        when(users.findByEmailIgnoreCase("maya@campus.edu")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(UserAccount.class))).thenThrow(failure);

        assertThatThrownBy(() -> initializer.run()).isSameAs(failure);

        verifyNoInteractions(listings, mongo);
    }

    @Test
    void staleTitleChecksStillUseStableInsertOnlyIdsForEveryCatalogItem() {
        UUID sellerId = UUID.randomUUID();
        when(maya.getId()).thenReturn(sellerId);
        when(users.findByEmailIgnoreCase("maya@campus.edu")).thenReturn(Optional.of(maya));
        when(users.findByEmailIgnoreCase("noah@campus.edu")).thenReturn(Optional.of(noah));
        // Both instances can observe "missing" before either one inserts the catalog.
        when(listings.existsByTitleIgnoreCase(anyString())).thenReturn(false);

        initializer.run();
        initializer.run();

        ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
        verify(mongo, times(30)).upsert(queries.capture(), updates.capture(), eq(Listing.class));
        List<Object> ids = queries.getAllValues().stream()
                .map(query -> query.getQueryObject().get("id")).toList();
        assertThat(ids.subList(0, 15)).doesNotHaveDuplicates();
        assertThat(ids.subList(15, 30)).containsExactlyElementsOf(ids.subList(0, 15));
        for (Update update : updates.getAllValues()) {
            Document operation = update.getUpdateObject();
            assertThat(operation.keySet()).containsExactly("$setOnInsert");
            Document inserted = operation.get("$setOnInsert", Document.class);
            assertThat(inserted.get("sellerId")).isEqualTo(sellerId);
            assertThat(inserted.get("status")).isEqualTo(ListingStatus.AVAILABLE);
            assertThat(inserted.keySet()).containsExactlyInAnyOrder(
                    "sellerId", "title", "description", "category", "price", "condition", "status", "createdAt");
        }
    }
}
