package com.cl2.integration.integration.transformation.jslt;

import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.lookup.application.ValueLookupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.schibsted.spt.data.jslt.Function;

import java.util.UUID;

public class LookupJsltFunction implements Function {

    private final ValueLookupService valueLookupService;

    public LookupJsltFunction(ValueLookupService valueLookupService) {
        this.valueLookupService = valueLookupService;
    }

    @Override
    public String getName() {
        return "lookup";
    }

    @Override
    public int getMinArguments() {
        return 2;
    }

    @Override
    public int getMaxArguments() {
        return 4;
    }

    @Override
    public JsonNode call(JsonNode input, JsonNode[] arguments) {
        if (arguments == null || arguments.length < 2) {
            return NullNode.getInstance();
        }

        String catalogCode = arguments[0] != null && !arguments[0].isNull() ? arguments[0].asText() : null;
        String sourceValue = arguments[1] != null && !arguments[1].isNull() ? arguments[1].asText() : null;
        String defaultValue = arguments.length >= 3 && arguments[2] != null && !arguments[2].isNull() ? arguments[2].asText() : null;
        String externalSource = arguments.length >= 4 && arguments[3] != null && !arguments[3].isNull() ? arguments[3].asText() : null;

        if (valueLookupService == null) {
            return defaultValue != null ? new TextNode(defaultValue) : (sourceValue != null ? new TextNode(sourceValue) : NullNode.getInstance());
        }

        UUID tenantId = null;
        try {
            tenantId = TenantContext.requireTenantId();
        } catch (Exception ignored) {
            // non-tenant context fallback
        }

        String targetValue = valueLookupService.lookup(tenantId, externalSource, catalogCode, sourceValue, defaultValue);
        if (targetValue != null) {
            return new TextNode(targetValue);
        } else if (defaultValue != null) {
            return new TextNode(defaultValue);
        } else if (sourceValue != null) {
            return new TextNode(sourceValue);
        } else {
            return NullNode.getInstance();
        }
    }
}
