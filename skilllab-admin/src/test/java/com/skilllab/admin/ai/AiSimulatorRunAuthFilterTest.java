package com.skilllab.admin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AiSimulatorRunAuthFilterTest {
    @Test
    void rejectsUnauthenticatedStartAndCrossOriginMutations() throws Exception {
        AiCreatorAuth auth = new AiCreatorAuth("maker", new BCryptPasswordEncoder().encode("password"));
        AiSimulatorRunAuthFilter filter = new AiSimulatorRunAuthFilter(auth, "http://localhost:5173");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/simulator/versions/v1/runs");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("must reject unauthenticated start"));
        assertEquals(401, response.getStatus());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AiCreatorAuth.SESSION_USER, "maker");
        request.setSession(session);
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://attacker.example");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("must reject forged origin"));
        assertEquals(403, response.getStatus());

        request.removeHeader("Origin");
        request.addHeader("Origin", "http://localhost:5173");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertEquals("maker", auth.requireUser(request)));
        assertEquals(200, response.getStatus());
    }

    @Test
    void loginIsOnlyOpenFromSimulatorOrigin() throws Exception {
        AiCreatorAuth auth = new AiCreatorAuth("maker", new BCryptPasswordEncoder().encode("password"));
        AiSimulatorRunAuthFilter filter = new AiSimulatorRunAuthFilter(auth, "http://localhost:5173");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/simulator/auth/login");
        request.addHeader("Origin", "https://attacker.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("must reject forged login"));
        assertEquals(403, response.getStatus());
        request.removeHeader("Origin");
        request.addHeader("Origin", "http://localhost:5173");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertEquals("/api/simulator/auth/login", request.getRequestURI()));
        assertEquals(200, response.getStatus());
    }
}
