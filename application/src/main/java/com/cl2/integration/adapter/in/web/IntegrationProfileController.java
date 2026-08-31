package com.cl2.integration.adapter.in.web;

import com.cl2.integration.adapter.in.web.dto.CreateIntegrationProfileRequest;
import com.cl2.integration.adapter.in.web.dto.IntegrationProfileResponse;
import com.cl2.integration.adapter.in.web.dto.MappingDryRunRequest;
import com.cl2.integration.adapter.in.web.dto.UpdateIntegrationProfileRequest;
import com.cl2.integration.application.IntegrationProfileService;
import com.cl2.integration.application.command.CreateIntegrationProfileCommand;
import com.cl2.integration.application.command.UpdateIntegrationProfileCommand;
import com.cl2.integration.domain.model.IntegrationProfileConfiguration;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.extraction.ExtractionDryRunResult;
import com.cl2.integration.integration.extraction.ExtractionDryRunService;
import com.cl2.integration.integration.transformation.MappingDryRunResult;
import com.cl2.integration.integration.transformation.MappingDryRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.cl2.integration.integration.sync.SyncState;
import com.cl2.integration.integration.sync.SyncStateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cl2.integration.adapter.in.web.dto.TriggerSyncResponse;
import com.cl2.integration.integration.sync.IntegrationSyncService;

@RestController
@RequestMapping("/api/v1/integration-profiles")
public class IntegrationProfileController {

    private final IntegrationProfileService service;
    private final IntegrationSyncService syncService;
    private final MappingDryRunService mappingDryRunService;
    private final ExtractionDryRunService extractionDryRunService;
    private final SyncStateRepository syncStateRepository;
    private final ObjectMapper objectMapper;

    public IntegrationProfileController(
            IntegrationProfileService service,
            IntegrationSyncService syncService,
            MappingDryRunService mappingDryRunService,
            ExtractionDryRunService extractionDryRunService,
            SyncStateRepository syncStateRepository,
            ObjectMapper objectMapper) {
        this.service = service;
        this.syncService = syncService;
        this.mappingDryRunService = mappingDryRunService;
        this.extractionDryRunService = extractionDryRunService;
        this.syncStateRepository = syncStateRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IntegrationProfileResponse create(@Valid @RequestBody CreateIntegrationProfileRequest request) {
        IntegrationProfileConfiguration configuration = request.configurationRequest().toDomain(objectMapper);
        return toResponse(service.create(TenantContext.requireTenantId(),
                new CreateIntegrationProfileCommand(request.businessDomain(), request.externalSource(), request.syncDirection(),
                        request.sourceOfTruth(), configuration)));
    }

    @PostMapping("/{profileId}/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TriggerSyncResponse triggerSync(@PathVariable UUID profileId) {
        syncService.triggerSync(TenantContext.requireTenantId(), profileId);
        return TriggerSyncResponse.triggered(profileId);
    }

    @GetMapping
    public List<IntegrationProfileResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(TenantContext.requireTenantId(), activeOnly).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{profileId}")
    public IntegrationProfileResponse get(@PathVariable UUID profileId) {
        return toResponse(service.get(TenantContext.requireTenantId(), profileId));
    }

    @PutMapping("/{profileId}")
    public IntegrationProfileResponse update(
            @PathVariable UUID profileId,
            @Valid @RequestBody UpdateIntegrationProfileRequest request) {
        IntegrationProfileConfiguration configuration = request.configurationRequest().toDomain(objectMapper);
        return toResponse(service.update(TenantContext.requireTenantId(), profileId,
                new UpdateIntegrationProfileCommand(request.businessDomain(), request.externalSource(), request.syncDirection(),
                        request.sourceOfTruth(), configuration, request.expectedVersion())));
    }

    @PostMapping("/{profileId}/pause")
    public IntegrationProfileResponse pause(@PathVariable UUID profileId) {
        return toResponse(service.pause(TenantContext.requireTenantId(), profileId));
    }

    @PostMapping("/{profileId}/resume")
    public IntegrationProfileResponse resume(@PathVariable UUID profileId) {
        return toResponse(service.resume(TenantContext.requireTenantId(), profileId));
    }

    @PostMapping("/{profileId}/mapping/dry-run")
    public MappingDryRunResult mappingDryRun(@PathVariable UUID profileId, @RequestBody MappingDryRunRequest request) {
        return mappingDryRunService.run(TenantContext.requireTenantId(), profileId, request.payload(), request.transformationJson());
    }

    @PostMapping("/{profileId}/extraction/dry-run")
    public ExtractionDryRunResult extractionDryRun(@PathVariable UUID profileId) {
        return extractionDryRunService.run(TenantContext.requireTenantId(), profileId);
    }

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID profileId) {
        service.deactivate(TenantContext.requireTenantId(), profileId);
    }

    private IntegrationProfileResponse toResponse(com.cl2.integration.application.IntegrationProfileView view) {
        Optional<SyncState> syncState = syncStateRepository.find(view.id());
        return IntegrationProfileResponse.from(view, syncState, objectMapper);
    }
}
