package com.allen.questionbank.common;

import java.time.Instant;

public record ErrorResponse(String code, String message, String requestId, Instant timestamp) {}
