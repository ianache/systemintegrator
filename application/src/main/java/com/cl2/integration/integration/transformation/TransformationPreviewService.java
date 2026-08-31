package com.cl2.integration.integration.transformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Stateless preview for an arbitrary transform script against a sample
 * payload — used by the flow canvas's per-node transformation editor, which
 * has no Integration Profile to anchor a dry-run against (unlike
 * MappingDryRunService, a different bounded context). Matches the design
 * mock's own footer text for this editor: "POST /api/v1/transformations/preview".
 */
@Service
public class TransformationPreviewService {

    private final Map<TransformationEngineType, PayloadTransformer> transformers = new EnumMap<>(TransformationEngineType.class);
    private final ObjectMapper objectMapper;

    public TransformationPreviewService(List<PayloadTransformer> transformerList, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        for (PayloadTransformer transformer : transformerList) {
            this.transformers.put(transformer.getEngineType(), transformer);
        }
    }

    public TransformationPreviewResult preview(TransformationEngineType engine, String script, String payload) {
        PayloadTransformer transformer = transformers.get(engine);
        if (transformer == null) {
            return TransformationPreviewResult.failure("No hay un motor de transformación registrado para " + engine + ".");
        }
        try {
            ObjectNode configNode = objectMapper.createObjectNode();
            configNode.put("engine", engine.name());
            configNode.put("script", script != null ? script : "");
            String configJson = objectMapper.writeValueAsString(configNode);
            String output = transformer.transform(payload, configJson);
            return TransformationPreviewResult.success(output);
        } catch (Exception ex) {
            return TransformationPreviewResult.failure(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }
}
