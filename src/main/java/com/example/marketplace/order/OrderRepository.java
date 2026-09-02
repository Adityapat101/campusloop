package com.example.marketplace.order;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    List<PurchaseOrder> findByBuyerIdOrderByCreatedAtDesc(UUID buyerId);
}
