package com.example.marketplace.listing;

import java.util.List;
import java.util.Locale;

import com.example.marketplace.common.NotFoundException;
import com.example.marketplace.user.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class ListingService {

    private final ListingRepository listings;
    private final UserRepository users;

    public ListingService(ListingRepository listings, UserRepository users) {
        this.listings = listings;
        this.users = users;
    }

    public Listing create(ListingRequest request) {
        if (!users.existsById(request.sellerId())) {
            throw new NotFoundException("Seller does not exist.");
        }
        return listings.save(new Listing(
                request.sellerId(),
                request.title().trim(),
                request.description().trim(),
                request.category().trim(),
                request.price(),
                request.condition()));
    }

    public List<Listing> browse(String category, String query) {
        String normalizedCategory = normalize(category);
        String normalizedQuery = normalize(query);
        return listings.findByStatusOrderByCreatedAtDesc(ListingStatus.AVAILABLE).stream()
                .filter(listing -> normalizedCategory == null
                        || listing.getCategory().toLowerCase(Locale.ROOT).equals(normalizedCategory))
                .filter(listing -> normalizedQuery == null
                        || listing.getTitle().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                        || listing.getDescription().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .toList();
    }

    public Listing get(String id) {
        return listings.findById(id)
                .orElseThrow(() -> new NotFoundException("Listing does not exist."));
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
