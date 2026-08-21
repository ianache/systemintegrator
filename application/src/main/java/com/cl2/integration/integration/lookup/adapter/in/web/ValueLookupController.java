package com.cl2.integration.integration.lookup.adapter.in.web;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.lookup.adapter.in.web.dto.CreateValueLookupRequest;
import com.cl2.integration.integration.lookup.adapter.in.web.dto.ValueLookupResponse;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lookups")
public class ValueLookupController {

    private final ValueLookupService service;

    public ValueLookupController(ValueLookupService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ValueLookupResponse create(@Valid @RequestBody CreateValueLookupRequest request) {
        return ValueLookupResponse.from(service.save(TenantContext.requireTenantId(), request));
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ValueLookupResponse> createBatch(@Valid @RequestBody List<@Valid CreateValueLookupRequest> requests) {
        return service.saveBatch(TenantContext.requireTenantId(), requests).stream()
                .map(ValueLookupResponse::from)
                .toList();
    }

    @GetMapping
    public List<ValueLookupResponse> list(
            @RequestParam String externalSource,
            @RequestParam String catalogCode) {
        return service.findAll(TenantContext.requireTenantId(), externalSource, catalogCode).stream()
                .map(ValueLookupResponse::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.deleteById(TenantContext.requireTenantId(), id);
    }
}
