package com.skilllab.admin.ai;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/auth")
public class AiCreatorAuthController {
    private final AiCreatorAuth auth;

    public AiCreatorAuthController(AiCreatorAuth auth) { this.auth = auth; }

    @PostMapping("/login")
    public Map<String, String> login(@RequestBody Map<String, String> credentials, HttpServletRequest request) {
        return auth.login(request, credentials.get("username"), credentials.get("password"));
    }

    @GetMapping("/me")
    public Map<String, String> me(HttpServletRequest request) {
        return java.util.Collections.singletonMap("userId", auth.requireUser(request));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) { auth.logout(request); }

    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public Map<String, String> unauthorized() { return java.util.Collections.singletonMap("error", "Invalid credentials"); }
}
