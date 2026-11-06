package dev.pitayaflow.backend;

// Adapted from LinkVideo f5451c4, server/service/AuthService.java and config/AuthInterceptor.java (MIT, Copyright 2026 Majst).
// Session token is opaque, stored by digest in Redis; public registration and plaintext migration are removed.
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
final class IdentityService {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final String bootstrapToken;
    private final SecureRandom random = new SecureRandom();
    IdentityService(JdbcTemplate db, StringRedisTemplate redis, @Value("${pf.bootstrap-token:}") String bootstrapToken) {
        this.db = db; this.redis = redis; this.bootstrapToken = bootstrapToken;
    }
    synchronized void bootstrap(String supplied, String username, String password) {
        if (bootstrapToken.length() < 32 || supplied == null || !MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8), bootstrapToken.getBytes(StandardCharsets.UTF_8))) throw ApiError.unauthorized();
        if (db.queryForObject("SELECT COUNT(*) FROM pf_user", Integer.class) != 0) throw ApiError.conflict("already initialized");
        create(username, password, "ADMIN");
    }
    void create(String username, String password, String role) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}") || password == null || password.length() < 12 || password.length() > 128)
            throw ApiError.bad("invalid username or password");
        try { db.update("INSERT INTO pf_user(username,password_hash,role) VALUES(?,?,?)", username, PasswordCodec.hash(password), role); }
        catch (DuplicateKeyException e) { throw ApiError.conflict("username exists"); }
    }
    Map<String,Object> login(String username, String password) {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,32}") || password == null) throw ApiError.unauthorized();
        String failKey = "pf:login-fail:" + username;
        String fail = redis.opsForValue().get(failKey);
        if (fail != null && Integer.parseInt(fail) >= 8) throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, "too many attempts");
        var users = db.queryForList("SELECT id,password_hash,role FROM pf_user WHERE username=?", username);
        if (users.size() != 1 || !PasswordCodec.matches(password, (String) users.getFirst().get("password_hash"))) {
            Long count = redis.opsForValue().increment(failKey);
            if (count != null && count == 1) redis.expire(failKey, 10, TimeUnit.MINUTES);
            throw ApiError.unauthorized();
        }
        redis.delete(failKey);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long id = ((Number) users.getFirst().get("id")).longValue();
        redis.opsForValue().set(key(token), Long.toString(id), 24, TimeUnit.HOURS);
        return Map.of("token", token, "user_id", id, "role", users.getFirst().get("role"));
    }
    long resolve(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw ApiError.unauthorized();
        String token = authorization.substring(7).trim();
        if (token.length() < 32 || token.length() > 200) throw ApiError.unauthorized();
        String value = redis.opsForValue().get(key(token));
        if (value == null) throw ApiError.unauthorized();
        return Long.parseLong(value);
    }
    void requireAdmin(long userId) {
        var roles = db.queryForList("SELECT role FROM pf_user WHERE id=?", String.class, userId);
        if (roles.size() != 1 || !roles.getFirst().equals("ADMIN")) throw ApiError.missing();
    }
    private String key(String token) {
        try { return "pf:session:" + Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
