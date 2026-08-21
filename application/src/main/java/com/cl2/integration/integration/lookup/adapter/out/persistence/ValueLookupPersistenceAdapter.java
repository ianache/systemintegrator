package com.cl2.integration.integration.lookup.adapter.out.persistence;

import com.cl2.integration.integration.lookup.domain.ValueLookup;
import com.cl2.integration.integration.lookup.domain.ValueLookupRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class ValueLookupPersistenceAdapter implements ValueLookupRepository {

    private final SpringDataValueLookupRepository repository;

    ValueLookupPersistenceAdapter(SpringDataValueLookupRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ValueLookup save(ValueLookup lookup) {
        Objects.requireNonNull(lookup, "lookup must not be null");
        ValueLookupJpaEntity entity = ValueLookupJpaEntity.from(lookup);
        ValueLookupJpaEntity saved = repository.save(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findTargetValue(UUID tenantId, String externalSource, String catalogCode, String sourceValue) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(externalSource, "externalSource must not be null");
        Objects.requireNonNull(catalogCode, "catalogCode must not be null");
        Objects.requireNonNull(sourceValue, "sourceValue must not be null");
        return repository.findTargetValue(tenantId, externalSource, catalogCode, sourceValue);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ValueLookup> findAll(UUID tenantId, String externalSource, String catalogCode) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(externalSource, "externalSource must not be null");
        Objects.requireNonNull(catalogCode, "catalogCode must not be null");
        return repository.findAllByTenantIdAndExternalSourceAndCatalogCode(tenantId, externalSource, catalogCode)
                .stream()
                .map(ValueLookupJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteById(UUID tenantId, UUID id) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(id, "id must not be null");
        repository.deleteByTenantIdAndId(tenantId, id);
    }
}
