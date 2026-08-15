package com.cl2.integration.vehicle.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Vehicle {

    private final UUID id;
    private final UUID tenantId;
    private final String vin;
    private final String brandCode;
    private final String modelCode;
    private final int modelYear;
    private final boolean active;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Vehicle(UUID id, UUID tenantId, String vin, String brandCode, String modelCode, int modelYear,
                    boolean active, Instant createdAt, Instant updatedAt, long version) {
        this.id = require(id, "id");
        this.tenantId = require(tenantId, "tenantId");
        this.vin = requireText(vin, "vin");
        this.brandCode = requireText(brandCode, "brandCode");
        this.modelCode = requireText(modelCode, "modelCode");
        if (modelYear < 1886 || modelYear > 3000) {
            throw new IllegalArgumentException("modelYear must be between 1886 and 3000");
        }
        this.modelYear = modelYear;
        this.active = active;
        this.createdAt = require(createdAt, "createdAt");
        this.updatedAt = require(updatedAt, "updatedAt");
        this.version = version;
    }

    public static Vehicle create(UUID id, UUID tenantId, String vin, String brandCode, String modelCode, int modelYear) {
        Instant now = Instant.now();
        return new Vehicle(id, tenantId, vin, brandCode, modelCode, modelYear, true, now, now, 0);
    }

    public static Vehicle rehydrate(UUID id, UUID tenantId, String vin, String brandCode, String modelCode,
                                    int modelYear, boolean active, Instant createdAt, Instant updatedAt, long version) {
        return new Vehicle(id, tenantId, vin, brandCode, modelCode, modelYear, active, createdAt, updatedAt, version);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String vin() { return vin; }
    public String brandCode() { return brandCode; }
    public String modelCode() { return modelCode; }
    public int modelYear() { return modelYear; }
    public boolean active() { return active; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
