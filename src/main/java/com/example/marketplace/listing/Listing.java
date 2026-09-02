package com.example.marketplace.listing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("listings")
@CompoundIndex(name = "listing_browse", def = "{'status': 1, 'category': 1, 'createdAt': -1}")
public class Listing {

    @Id
    private String id;
    private UUID sellerId;
    private String title;
    private String description;
    private String category;
    private BigDecimal price;
    private ItemCondition condition;
    private ListingStatus status;
    private Instant createdAt;

    protected Listing() {
    }

    public Listing(UUID sellerId, String title, String description, String category,
            BigDecimal price, ItemCondition condition) {
        this.sellerId = sellerId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.price = price;
        this.condition = condition;
        this.status = ListingStatus.AVAILABLE;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ItemCondition getCondition() {
        return condition;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
