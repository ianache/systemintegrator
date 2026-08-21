package com.cl2.integration.integration.lookup.application;

import com.cl2.integration.integration.lookup.adapter.in.web.dto.CreateValueLookupRequest;
import com.cl2.integration.integration.lookup.domain.ValueLookup;
import com.cl2.integration.integration.lookup.domain.ValueLookupRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValueLookupService {

    private final ValueLookupRepository repository;
    private final Map<LookupCacheKey, Optional<String>> cache = new ConcurrentHashMap<>();

    public ValueLookupService(ValueLookupRepository repository) {
        this.repository = repository;
    }

    public String lookup(UUID tenantId, String externalSource, String catalogCode, String sourceValue, String defaultValue) {
        if (tenantId == null || externalSource == null || catalogCode == null || sourceValue == null) {
            return defaultValue;
        }

        LookupCacheKey key = new LookupCacheKey(tenantId, externalSource, catalogCode, sourceValue);
        Optional<String> cached = cache.computeIfAbsent(key, k ->
                repository.findTargetValue(k.tenantId(), k.externalSource(), k.catalogCode(), k.sourceValue())
        );

        return cached.orElse(defaultValue);
    }

    public List<ValueLookup> findAll(UUID tenantId, String externalSource, String catalogCode) {
        return repository.findAll(tenantId, externalSource, catalogCode);
    }

    @Transactional
    public ValueLookup save(UUID tenantId, CreateValueLookupRequest request) {
        ValueLookup lookup = ValueLookup.create(
                request.id(),
                tenantId,
                request.externalSource(),
                request.catalogCode(),
                request.sourceValue(),
                request.targetValue(),
                request.description(),
                request.active() == null || request.active()
        );

        ValueLookup saved = repository.save(lookup);
        invalidateCache(tenantId, request.externalSource(), request.catalogCode(), request.sourceValue());
        return saved;
    }

    @Transactional
    public List<ValueLookup> saveBatch(UUID tenantId, List<CreateValueLookupRequest> requests) {
        return requests.stream()
                .map(request -> save(tenantId, request))
                .toList();
    }

    @Transactional
    public void deleteById(UUID tenantId, UUID id) {
        repository.deleteById(tenantId, id);
        invalidateTenantCache(tenantId);
    }

    public void invalidateCache(UUID tenantId, String externalSource, String catalogCode, String sourceValue) {
        cache.remove(new LookupCacheKey(tenantId, externalSource, catalogCode, sourceValue));
    }

    public void invalidateTenantCache(UUID tenantId) {
        cache.keySet().removeIf(k -> k.tenantId().equals(tenantId));
    }

    public void clearCache() {
        cache.clear();
    }

    private record LookupCacheKey(UUID tenantId, String externalSource, String catalogCode, String sourceValue) {
    }
}
