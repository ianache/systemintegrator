package com.cl2.integration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateFlowRequest(@NotBlank String code, @NotBlank String name) {
}
