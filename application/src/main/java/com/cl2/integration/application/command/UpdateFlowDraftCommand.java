package com.cl2.integration.application.command;

public record UpdateFlowDraftCommand(String name, String triggerSummary, String draftGraph, long expectedVersion) {
}
