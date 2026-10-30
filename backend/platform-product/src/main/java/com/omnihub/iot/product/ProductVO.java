package com.omnihub.iot.product;

import java.time.Instant;

public record ProductVO(Long id, String name, String productKey, String category, String protocol, String status,
        String description, Long deviceCount, Integer thingModelVersion, Instant createdAt, Instant updatedAt) {
}
