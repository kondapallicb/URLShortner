package com.kondapallicb.urlshortener.api;

import jakarta.validation.constraints.NotBlank;

public record ApprovalRequest(
        @NotBlank String evidenceHash,
        String comment
) {
}
