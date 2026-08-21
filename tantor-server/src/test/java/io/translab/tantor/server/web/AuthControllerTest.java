package io.translab.tantor.server.web;

import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.repository.LdapConfigRepository;
import io.translab.tantor.server.repository.UserRepository;
import io.translab.tantor.server.service.LdapService;
import io.translab.tantor.server.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

public class AuthControllerTest {

    private AuthController authController;
    private AuditService auditService;

    @BeforeEach
    public void setup() {
        JwtUtils jwtUtils = mock(JwtUtils.class);
        LdapConfigRepository ldapConfigRepository = mock(LdapConfigRepository.class);
        LdapService ldapService = mock(LdapService.class);
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        auditService = mock(AuditService.class);

        authController = new AuthController(jwtUtils, ldapConfigRepository, ldapService, userRepository, passwordEncoder, auditService);
    }

    @Test
    public void testRateLimiting429() {
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("testuser");
        request.setPassword("password");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("192.168.1.100");

        // The IP limit is 10, the User limit is 5. So after 5 attempts, the user is rate limited.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<?> response = authController.authenticateUser(request, servletRequest);
            // 401 because mock repository returns null for LDAP config and user
            assertEquals(401, response.getStatusCodeValue(), "Attempt " + (i+1) + " should be 401");
        }

        // The 6th attempt should return 429
        ResponseEntity<?> response = authController.authenticateUser(request, servletRequest);
        assertEquals(429, response.getStatusCodeValue(), "Attempt 6 should be 429 Too Many Requests");
    }

    @Test
    public void testRateLimiterConcurrency() throws InterruptedException {
        AuthController.LoginRequest request = new AuthController.LoginRequest();
        request.setUsername("concurrentuser");
        request.setPassword("password");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("192.168.1.101");

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger tooManyRequestsCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    ResponseEntity<?> response = authController.authenticateUser(request, servletRequest);
                    if (response.getStatusCodeValue() == 429) {
                        tooManyRequestsCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore
                } finally {
                    done.countDown();
                }
            });
        }

        latch.countDown(); // start all threads
        done.await(); // wait for completion

        // Only 5 should get through, meaning 15 should get 429
        assertEquals(15, tooManyRequestsCount.get(), "Exactly 15 requests should be rate limited");
    }

    @Test
    public void testRateLimiterMemoryBounded() {
        // We will simulate 2000 different IPs making 1 request each.
        // The maxEntries is 1000, so the map should never exceed 1000.
        Object ipLimiter = ReflectionTestUtils.getField(authController, "ipLimiter");
        Map<?, ?> store = (Map<?, ?>) ReflectionTestUtils.getField(ipLimiter, "store");

        for (int i = 0; i < 2000; i++) {
            AuthController.LoginRequest request = new AuthController.LoginRequest();
            request.setUsername("user" + i);
            request.setPassword("password");

            MockHttpServletRequest servletRequest = new MockHttpServletRequest();
            servletRequest.setRemoteAddr("10.0.0." + (i % 256)); // Different IPs don't matter as much as key uniqueness
            
            // Just directly call tryAcquire
            try {
                java.lang.reflect.Method tryAcquire = ipLimiter.getClass().getMethod("tryAcquire", String.class);
                tryAcquire.invoke(ipLimiter, "10.0.0." + i);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        assertTrue(store.size() <= 1000, "Store size should be bounded to 1000, actual: " + store.size());
    }
}
