package com.cl2.integration.adapter.in.web;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.credential.CredentialCatalogService;
import com.cl2.integration.integration.credential.CredentialSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credentials")
public class CredentialCatalogController {

    private final CredentialCatalogService service;

    public CredentialCatalogController(CredentialCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<CredentialSummary> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return service.list(tenantId);
    }
}
