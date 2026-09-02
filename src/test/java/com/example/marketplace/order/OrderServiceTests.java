package com.example.marketplace.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.example.marketplace.common.ConflictException;
import com.example.marketplace.listing.ItemCondition;
import com.example.marketplace.listing.Listing;
import com.example.marketplace.listing.ListingRepository;
import com.example.marketplace.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class OrderServiceTests {

    @Mock UserRepository users;
    @Mock ListingRepository listings;
    @Mock OrderRepository orders;
    @Mock MongoTemplate mongo;

    private OrderService service;
    private UUID sellerId;
    private UUID buyerId;
    private Listing listing;

    @BeforeEach
    void setUp() {
        service = new OrderService(users, listings, orders, mongo);
        sellerId = UUID.randomUUID();
        buyerId = UUID.randomUUID();
        listing = new Listing(sellerId, "Desk lamp", "Bright", "Dorm",
                new BigDecimal("15.00"), ItemCondition.GOOD);
    }

    @Test
    void atomicallyClaimsAnAvailableListingBeforeWritingTheOrder() {
        when(users.existsById(buyerId)).thenReturn(true);
        when(listings.findById("listing-1")).thenReturn(Optional.of(listing));
        when(mongo.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Listing.class))).thenReturn(listing);
        when(orders.save(any(PurchaseOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PurchaseOrder order = service.buy(new OrderRequest(buyerId, "listing-1"));

        assertThat(order.getBuyerId()).isEqualTo(buyerId);
        assertThat(order.getPrice()).isEqualByComparingTo("15.00");
        verify(orders).save(any(PurchaseOrder.class));
    }

    @Test
    void rejectsAListingThatAnotherBuyerAlreadyClaimed() {
        when(users.existsById(buyerId)).thenReturn(true);
        when(listings.findById("listing-1")).thenReturn(Optional.of(listing));
        when(mongo.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Listing.class))).thenReturn(null);

        assertThatThrownBy(() -> service.buy(new OrderRequest(buyerId, "listing-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already been sold");
    }
}
