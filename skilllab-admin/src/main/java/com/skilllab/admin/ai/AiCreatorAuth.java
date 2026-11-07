package com.skilllab.admin.ai;

import java.util.Collections;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AiCreatorAuth {
    public static final String SESSION_USER = "skilllab.creator.userId";
    private final String username;
    private final String passwordHash;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AiCreatorAuth(@Value("${skilllab.ai.creator-username:}") String username,
            @Value("${skilllab.ai.creator-password-hash:}") String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Map<String, String> login(HttpServletRequest request, String user, String password) {
        if (username == null || username.isEmpty() || passwordHash == null || passwordHash.isEmpty()) {
            throw new IllegalStateException("Creator login is not configured");
        }
        if (!username.equals(user) || password == null || !encoder.matches(password, passwordHash)) {
            throw new SecurityException("Invalid credentials");
        }
        HttpSession previous = request.getSession(false);
        if (previous != null) previous.invalidate();
        request.getSession(true).setAttribute(SESSION_USER, username);
        return Collections.singletonMap("userId", username);
    }

    public String requireUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER);
        if (!(user instanceof String) || !username.equals(user)) throw new SecurityException("Creator login required");
        return (String) user;
    }

    public void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
