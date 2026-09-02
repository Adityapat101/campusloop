package com.example.marketplace.order;

import java.util.List;
import java.util.UUID;

import com.example.marketplace.common.ConflictException;
import com.example.marketplace.common.NotFoundException;
import com.example.marketplace.listing.Listing;
import com.example.marketplace.listing.ListingRepository;
import com.example.marketplace.listing.ListingStatus;
import com.example.marketplace.user.UserRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final UserRepository users;
    private final ListingRepository listings;
    private final OrderRepository orders;
    private final MongoTemplate mongo;

    public OrderService(UserRepository users, ListingRepository listings,
            OrderRepository orders, MongoTemplate mongo) {
        this.users = users;
        this.listings = listings;
        this.orders = orders;
        this.mongo = mongo;
    }

    public PurchaseOrder buy(OrderRequest request) {
        if (!users.existsById(request.buyerId())) {
            throw new NotFoundException("Buyer does not exist.");
        }

        Listing candidate = listings.findById(request.listingId())
                .orElseThrow(() -> new NotFoundException("Listing does not exist."));
        if (candidate.getSellerId().equals(request.buyerId())) {
            throw new ConflictException("A seller cannot buy their own listing.");
        }

        Query availableListing = Query.query(Criteria.where("id").is(request.listingId())
                .and("status").is(ListingStatus.AVAILABLE));
        Listing sold = mongo.findAndModify(
                availableListing,
                new Update().set("status", ListingStatus.SOLD),
                FindAndModifyOptions.options().returnNew(true),
                Listing.class);

        if (sold == null) {
            throw new ConflictException("This listing has already been sold.");
        }

        try {
            return orders.save(new PurchaseOrder(request.buyerId(), sold.getId(), sold.getPrice()));
        } catch (RuntimeException exception) {
            mongo.updateFirst(
                    Query.query(Criteria.where("id").is(request.listingId()).and("status").is(ListingStatus.SOLD)),
                    new Update().set("status", ListingStatus.AVAILABLE), Listing.class);
            throw exception;
        }
    }

    public List<PurchaseOrder> forBuyer(UUID buyerId) {
        if (!users.existsById(buyerId)) {
            throw new NotFoundException("Buyer does not exist.");
        }
        return orders.findByBuyerIdOrderByCreatedAtDesc(buyerId);
    }
}
