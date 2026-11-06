package dev.pitayaflow.backend;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class ApiError extends ResponseStatusException {
    ApiError(HttpStatus status, String reason) { super(status, reason); }
    static ApiError bad(String reason) { return new ApiError(HttpStatus.BAD_REQUEST, reason); }
    static ApiError conflict(String reason) { return new ApiError(HttpStatus.CONFLICT, reason); }
    static ApiError missing() { return new ApiError(HttpStatus.NOT_FOUND, "resource not found"); }
    static ApiError unauthorized() { return new ApiError(HttpStatus.UNAUTHORIZED, "login required"); }
}
