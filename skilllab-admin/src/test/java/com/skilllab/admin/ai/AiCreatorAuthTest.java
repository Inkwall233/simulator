package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AiCreatorAuthTest {
    @Test
    void loginRequiresCredentialsAndRotatesSession() {
        AiCreatorAuth auth = new AiCreatorAuth("maker", new BCryptPasswordEncoder().encode("correct-password"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(new MockHttpSession());
        String oldId = request.getSession().getId();
        assertThrows(SecurityException.class, () -> auth.login(request, "maker", "wrong"));
        assertNull(request.getSession().getAttribute(AiCreatorAuth.SESSION_USER));
        assertEquals("maker", auth.login(request, "maker", "correct-password").get("userId"));
        assertNotEquals(oldId, request.getSession().getId());
        assertEquals("maker", auth.requireUser(request));
        auth.logout(request);
        assertThrows(SecurityException.class, () -> auth.requireUser(request));
    }

    @Test
    void unconfiguredAuthenticationFailsClosed() {
        AiCreatorAuth auth = new AiCreatorAuth("", "");
        assertThrows(IllegalStateException.class, () -> auth.login(new MockHttpServletRequest(), "maker", "password"));
    }

    @Test
    void filterRejectsUnauthenticatedAndCrossOriginMutations() throws Exception {
        AiCreatorAuth auth = new AiCreatorAuth("maker", new BCryptPasswordEncoder().encode("correct-password"));
        AiCreatorAuthFilter filter = new AiCreatorAuthFilter(auth, "http://localhost:5174");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/device-projects");
        request.addHeader("Origin", "http://localhost:5174");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("must reject"));
        assertEquals(401, response.getStatus());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AiCreatorAuth.SESSION_USER, "maker");
        request.setSession(session);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://attacker.example");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("must reject origin"));
        assertEquals(403, response.getStatus());
        request.removeHeader("Origin");
        request.addHeader("Origin", "http://localhost:5174");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertEquals("maker", auth.requireUser(request)));
        assertEquals(200, response.getStatus());
    }

    @Test
    void loginEndpointIsOpenOnlyFromConfiguredOriginAndLogoutRevokesSession() throws Exception {
        AiCreatorAuth auth = new AiCreatorAuth("maker", new BCryptPasswordEncoder().encode("correct-password"));
        AiCreatorAuthFilter filter = new AiCreatorAuthFilter(auth, "http://localhost:5174");
        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/api/ai/auth/login");
        login.addHeader("Origin", "http://localhost:5174");
        filter.doFilter(login, new MockHttpServletResponse(), (req, res) ->
                assertEquals("maker", auth.login(login, "maker", "correct-password").get("userId")));
        MockHttpServletRequest me = new MockHttpServletRequest("GET", "/api/ai/auth/me");
        me.setSession((MockHttpSession) login.getSession());
        filter.doFilter(me, new MockHttpServletResponse(), (req, res) ->
                assertEquals("maker", auth.requireUser(me)));
        auth.logout(me);
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(me, denied, (req, res) -> fail("logged-out session must be denied"));
        assertEquals(401, denied.getStatus());
    }
}
