package com.cl2.integration.domain.model.customer;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerDomainTest {

    @Test
    void shouldCreateCanonicalCustomer() {
        UUID tenantId = UUID.randomUUID();
        Customer customer = Customer.create(
            tenantId, 
            "CLI-001", 
            "20100012345", 
            "EMPRESA TEST SAC", 
            "TEST", 
            "test@company.com", 
            "+51999999999", 
            "AV. PERU 123", 
            "PE"
        );

        assertThat(customer.tenantId()).isEqualTo(tenantId);
        assertThat(customer.customerId()).isEqualTo("CLI-001");
        assertThat(customer.taxId()).isEqualTo("20100012345");
        assertThat(customer.legalName()).isEqualTo("EMPRESA TEST SAC");
        assertThat(customer.tradeName()).isEqualTo("TEST");
        assertThat(customer.email()).isEqualTo("test@company.com");
        assertThat(customer.phone()).isEqualTo("+51999999999");
        assertThat(customer.address()).isEqualTo("AV. PERU 123");
        assertThat(customer.countryCode()).isEqualTo("PE");
    }

    @Test
    void shouldValidateCustomerFields() {
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> Customer.create(null, "CLI-001", "20100012345", "EMPRESA TEST SAC", "TEST", "test@company.com", "+51999999999", "AV. PERU 123", "PE"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("tenantId must not be null");

        assertThatThrownBy(() -> Customer.create(tenantId, "", "20100012345", "EMPRESA TEST SAC", "TEST", "test@company.com", "+51999999999", "AV. PERU 123", "PE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("customerId must not be blank");
    }

    @Test
    void shouldCreateCustomerCreatedEvent() {
        UUID tenantId = UUID.randomUUID();
        Customer customer = Customer.create(
            tenantId, 
            "CLI-001", 
            "20100012345", 
            "EMPRESA TEST SAC", 
            "TEST", 
            "test@company.com", 
            "+51999999999", 
            "AV. PERU 123", 
            "PE"
        );

        CustomerCreatedEvent event = CustomerCreatedEvent.from(customer);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.customerId()).isEqualTo("CLI-001");
        assertThat(event.taxId()).isEqualTo("20100012345");
        assertThat(event.legalName()).isEqualTo("EMPRESA TEST SAC");
        assertThat(event.tradeName()).isEqualTo("TEST");
        assertThat(event.email()).isEqualTo("test@company.com");
        assertThat(event.phone()).isEqualTo("+51999999999");
        assertThat(event.address()).isEqualTo("AV. PERU 123");
        assertThat(event.countryCode()).isEqualTo("PE");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void shouldCreateCustomerUpdatedEvent() {
        UUID tenantId = UUID.randomUUID();
        Customer customer = Customer.create(
            tenantId, 
            "CLI-001", 
            "20100012345", 
            "EMPRESA TEST SAC", 
            "TEST", 
            "test@company.com", 
            "+51999999999", 
            "AV. PERU 123", 
            "PE"
        );

        CustomerUpdatedEvent event = CustomerUpdatedEvent.from(customer);

        assertThat(event.eventId()).isNotNull();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.customerId()).isEqualTo("CLI-001");
        assertThat(event.taxId()).isEqualTo("20100012345");
        assertThat(event.legalName()).isEqualTo("EMPRESA TEST SAC");
        assertThat(event.tradeName()).isEqualTo("TEST");
        assertThat(event.email()).isEqualTo("test@company.com");
        assertThat(event.phone()).isEqualTo("+51999999999");
        assertThat(event.address()).isEqualTo("AV. PERU 123");
        assertThat(event.countryCode()).isEqualTo("PE");
        assertThat(event.occurredAt()).isNotNull();
    }
}
