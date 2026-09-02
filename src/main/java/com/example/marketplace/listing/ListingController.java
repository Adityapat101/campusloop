package com.example.marketplace.listing;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingService service;

    public ListingController(ListingService service) {
        this.service = service;
    }

    @GetMapping
    List<Listing> browse(
            @RequestParam(required = false) String category,
            @RequestParam(required = false, name = "q") String query) {
        return service.browse(category, query);
    }

    @GetMapping("/{id}")
    Listing get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    ResponseEntity<Listing> create(@Valid @RequestBody ListingRequest request) {
        Listing saved = service.create(request);
        return ResponseEntity.created(URI.create("/api/listings/" + saved.getId())).body(saved);
    }
}
