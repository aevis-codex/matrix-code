package com.matrixcode.identity.application;

import java.time.Instant;

public record IssuedActorToken(String userId, String token, Instant expiresAt) {
}
