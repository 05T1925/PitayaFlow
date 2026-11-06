package dev.pitayaflow.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@RestController
@ConditionalOnProperty(name="pf.role",havingValue="api",matchIfMissing=true)
@RequestMapping("/api/v1")
final class IdentityController {
    private final IdentityService identity;
    IdentityController(IdentityService identity) { this.identity = identity; }
    record Credentials(String username, String password) {}
    record Bootstrap(String token, String username, String password) {}
    @PostMapping("/bootstrap") @ResponseStatus(HttpStatus.CREATED)
    Map<String,String> bootstrap(@RequestBody Bootstrap b) { identity.bootstrap(b.token(), b.username(), b.password()); return Map.of("status", "created"); }
    @PostMapping("/login") Map<String,Object> login(@RequestBody Credentials c) { return identity.login(c.username(), c.password()); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    Map<String,String> create(@RequestBody Credentials c, HttpServletRequest request) {
        identity.requireAdmin((long) request.getAttribute("pf_user_id")); identity.create(c.username(), c.password(), "USER");
        return Map.of("status", "created");
    }
}
