package com.example.marketplace.order;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(@NotNull UUID buyerId, @NotBlank String listingId) {
}
