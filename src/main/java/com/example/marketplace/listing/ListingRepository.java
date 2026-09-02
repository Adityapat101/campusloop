package com.example.marketplace.listing;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ListingRepository extends MongoRepository<Listing, String> {

    boolean existsByTitleIgnoreCase(String title);

    List<Listing> findByStatusOrderByCreatedAtDesc(ListingStatus status);
}
