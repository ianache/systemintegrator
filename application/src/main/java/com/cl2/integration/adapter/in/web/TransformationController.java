package com.cl2.integration.adapter.in.web;

import com.cl2.integration.adapter.in.web.dto.TransformationPreviewRequest;
import com.cl2.integration.infrastructure.tenant.TenantContext;
import com.cl2.integration.integration.transformation.TransformationEngineType;
import com.cl2.integration.integration.transformation.TransformationPreviewResult;
import com.cl2.integration.integration.transformation.TransformationPreviewService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transformations")
public class TransformationController {

    private final TransformationPreviewService previewService;

    public TransformationController(TransformationPreviewService previewService) {
        this.previewService = previewService;
    }

    @PostMapping("/preview")
    public TransformationPreviewResult preview(@RequestBody TransformationPreviewRequest request) {
        TenantContext.requireTenantId();
        TransformationEngineType engine = TransformationEngineType.fromString(request.engine());
        return previewService.preview(engine, request.script(), request.payload());
    }
}
