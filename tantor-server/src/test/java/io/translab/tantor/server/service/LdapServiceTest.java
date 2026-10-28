package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.dto.LdapDTOs.LdapTestResponse;
import io.translab.tantor.server.security.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.naming.AuthenticationException;
import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.TimeLimitExceededException;
import org.mockito.ArgumentCaptor;
import java.util.Hashtable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LdapServiceTest {

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private DirContext bindCtx;

    @Mock
    private DirContext userCtx;

    @Mock
    private NamingEnumeration<SearchResult> searchResults;

    private LdapService ldapService;
    private LdapConfig config;

    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASSWORD = "adminPassword123";
    private static final String USERNAME = "alice";
    private static final String USER_PASSWORD = "secretUserPass";
    private static final String USER_DN = "cn=alice,ou=users,dc=example,dc=com";

    @BeforeEach
    void setUp() throws Exception {
        ldapService = spy(new LdapService(encryptionService));

        config = new LdapConfig();
        config.setEnabled(true);
        config.setServerUrl("ldap://localhost:389");
        config.setBindDn(BIND_DN);
        config.setUserSearchBase("ou=users,dc=example,dc=com");
        config.setUserSearchFilter("(sAMAccountName={username})");
        config.setGroupSearchBase(null); // Keep group search disabled to focus on Part 1

        lenient().doAnswer(invocation -> {
            Hashtable<String, String> env = invocation.getArgument(0);
            String principal = env.get(Context.SECURITY_PRINCIPAL);
            if (BIND_DN.equals(principal)) {
                return bindCtx;
            } else {
                return userCtx;
            }
        }).when(ldapService).createDirContext(any());
    }

    @Test
    @DisplayName("No user matches: authentication fails with expected user-not-found response")
    void testAuthenticate_noUserMatches_returnsUserNotFound() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), eq("(sAMAccountName=alice)"), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("User not found in directory");
        assertThat(response.getUserDn()).isNull();

        verify(searchResults).close();
        verify(bindCtx).close();
        // Verify only admin bind context was created, no user context
        verify(ldapService, times(1)).createDirContext(any());
        verify(userCtx, never()).close();
    }

    @Test
    @DisplayName("Exactly one user matches and user bind succeeds: authentication proceeds successfully")
    void testAuthenticate_singleUserMatch_succeeds() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), eq("(sAMAccountName=alice)"), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);

        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
        assertThat(response.getUserDn()).isEqualTo(USER_DN);

        verify(searchResults).close();
        verify(userCtx).close();
        verify(bindCtx).close();
        // 1 administrative bind context + 1 user authentication context
        verify(ldapService, times(2)).createDirContext(any());
    }

    @Test
    @DisplayName("Multiple users match: authentication fails with ambiguity message, and no user bind is attempted")
    void testAuthenticate_multipleUsersMatch_rejectsAmbiguous() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), eq("(sAMAccountName=alice)"), any(SearchControls.class)))
                .thenReturn(searchResults);
        // hasMore() returns true for first user, then true again for second user
        // (ambiguity)
        when(searchResults.hasMore()).thenReturn(true, true);

        SearchResult result1 = new SearchResult("alice1", null, new BasicAttributes());
        result1.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result1);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Multiple users found in directory, authentication is ambiguous");
        assertThat(response.getUserDn()).isNull();

        verify(searchResults).close();
        verify(bindCtx).close();
        // Crucial: verify user context was NEVER created or bound
        verify(ldapService, times(1)).createDirContext(any());
        verify(userCtx, never()).close();
    }

    @Test
    @DisplayName("User search throws NamingException: authentication fails without attempting a user bind")
    void testAuthenticate_userSearchThrowsNamingException_failsWithoutUserBind() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class)))
                .thenThrow(new CommunicationException("LDAP search timed out"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("LDAP search timed out");
        assertThat(response.getUserDn()).isNull();

        verify(bindCtx).close();
        verify(ldapService, times(1)).createDirContext(any());
        verify(userCtx, never()).close();
    }

    @Test
    @DisplayName("Enumeration is closed when no results are found")
    void testAuthenticate_enumerationClosedWhenNoResultsFound() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        verify(searchResults, times(1)).close();
    }

    @Test
    @DisplayName("Enumeration is closed when multiple results are found")
    void testAuthenticate_enumerationClosedWhenMultipleResultsFound() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, true);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        verify(searchResults, times(1)).close();
    }

    @Test
    @DisplayName("Enumeration is closed after a successful unique-user lookup")
    void testAuthenticate_enumerationClosedAfterSuccessfulUniqueUserLookup() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        verify(searchResults, times(1)).close();
    }

    @Test
    @DisplayName("Enumeration is closed when reading results throws NamingException")
    void testAuthenticate_enumerationClosedWhenReadingResultsThrows() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenThrow(new NamingException("Stream corrupted"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Stream corrupted");

        verify(searchResults, times(1)).close();
        verify(bindCtx).close();
        verify(ldapService, times(1)).createDirContext(any());
        verify(userCtx, never()).close();
    }

    @Test
    @DisplayName("User bind fails: returns failure response after closing user search enumeration")
    void testAuthenticate_userBindFails() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        doAnswer(invocation -> {
            Hashtable<String, String> env = invocation.getArgument(0);
            String principal = env.get(Context.SECURITY_PRINCIPAL);
            if (BIND_DN.equals(principal)) {
                return bindCtx;
            } else {
                throw new AuthenticationException("Invalid user credentials");
            }
        }).when(ldapService).createDirContext(any());

        LdapTestResponse response = ldapService.authenticate(USERNAME, "wrongPassword", config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Invalid user credentials");

        verify(searchResults).close();
        verify(bindCtx).close();
        verify(ldapService, times(2)).createDirContext(any());
    }

    @Test
    @DisplayName("Bind context close exception is safely ignored in finally block")
    void testAuthenticate_bindCtxCloseThrows_handledSafely() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        doThrow(new NamingException("Socket already closed")).when(bindCtx).close();

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
    }

    @Test
    @DisplayName("Enumeration close exception is safely ignored in finally block")
    void testAuthenticate_enumerationCloseThrows_handledSafely() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        doThrow(new NamingException("Enumeration close failed")).when(searchResults).close();

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
    }

    // ─── Part 2: Timeout Configuration and Validation Tests ───────────

    @Test
    @DisplayName("Default configuration sets connection and read timeouts to 10 seconds (10000 ms)")
    void testCreateEnv_defaultTimeout_configuresConnectAndReadTimeout() {
        assertThat(config.getConnectionTimeout()).isEqualTo(10);
        Hashtable<String, String> env = ldapService.createEnv(config, BIND_DN, BIND_PASSWORD);

        assertThat(env.get("com.sun.jndi.ldap.connect.timeout")).isEqualTo("10000");
        assertThat(env.get("com.sun.jndi.ldap.read.timeout")).isEqualTo("10000");
    }

    @Test
    @DisplayName("Lower boundary (1 second) correctly sets 1000 ms in environment")
    void testCreateEnv_lowerBoundary_oneSecondConfigures1000ms() {
        config.setConnectionTimeout(1);
        Hashtable<String, String> env = ldapService.createEnv(config, BIND_DN, BIND_PASSWORD);

        assertThat(env.get("com.sun.jndi.ldap.connect.timeout")).isEqualTo("1000");
        assertThat(env.get("com.sun.jndi.ldap.read.timeout")).isEqualTo("1000");
    }

    @Test
    @DisplayName("Upper boundary (60 seconds) correctly sets 60000 ms in environment")
    void testCreateEnv_upperBoundary_sixtySecondsConfigures60000ms() {
        config.setConnectionTimeout(60);
        Hashtable<String, String> env = ldapService.createEnv(config, BIND_DN, BIND_PASSWORD);

        assertThat(env.get("com.sun.jndi.ldap.connect.timeout")).isEqualTo("60000");
        assertThat(env.get("com.sun.jndi.ldap.read.timeout")).isEqualTo("60000");
    }

    @Test
    @DisplayName("Zero timeout is rejected without attempting LDAP connection or bind")
    void testAuthenticate_zeroTimeout_rejectedWithoutLdapBind() throws Exception {
        config.setConnectionTimeout(0);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("LDAP connection timeout must be between 1 and 60 seconds");
        verify(ldapService, never()).createDirContext(any());
    }

    @Test
    @DisplayName("Negative timeout is rejected without attempting LDAP connection or bind")
    void testAuthenticate_negativeTimeout_rejectedWithoutLdapBind() throws Exception {
        config.setConnectionTimeout(-5);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("LDAP connection timeout must be between 1 and 60 seconds");
        verify(ldapService, never()).createDirContext(any());
    }

    @Test
    @DisplayName("Timeout above maximum (60s) is rejected without attempting LDAP connection or bind")
    void testAuthenticate_aboveMaxTimeout_rejectedWithoutLdapBind() throws Exception {
        config.setConnectionTimeout(61);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("LDAP connection timeout must be between 1 and 60 seconds");
        verify(ldapService, never()).createDirContext(any());
    }

    @Test
    @DisplayName("testConnection rejects invalid timeout without attempting LDAP bind")
    void testTestConnection_invalidTimeout_rejectedWithoutLdapBind() throws Exception {
        config.setConnectionTimeout(0);

        LdapTestResponse response = ldapService.testConnection(config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Invalid LDAP configuration.");
        assertThat(response.getMessage()).doesNotContain("LDAP connection timeout must be between 1 and 60 seconds");
        verify(ldapService, never()).createDirContext(any());
    }

    @Test
    @DisplayName("testConnection applies configured timeout to LDAP environment")
    void testTestConnection_validTimeout_appliesTimeoutToEnv() throws Exception {
        config.setConnectionTimeout(15);
        DirContext testCtx = mock(DirContext.class);
        doReturn(testCtx).when(ldapService).createDirContext(any());

        LdapTestResponse response = ldapService.testConnection(config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Successfully bound to LDAP server");
        verify(testCtx).close();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Hashtable<String, String>> envCaptor = ArgumentCaptor.forClass(Hashtable.class);
        verify(ldapService).createDirContext(envCaptor.capture());
        assertThat(envCaptor.getValue().get("com.sun.jndi.ldap.connect.timeout")).isEqualTo("15000");
        assertThat(envCaptor.getValue().get("com.sun.jndi.ldap.read.timeout")).isEqualTo("15000");
    }

    @Test
    @DisplayName("User search controls receive the configured timeout in milliseconds")
    void testAuthenticate_userSearchControls_receivesConfiguredTimeLimit() throws Exception {
        config.setConnectionTimeout(25);
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<SearchControls> captor = ArgumentCaptor.forClass(SearchControls.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), anyString(), captor.capture());
        assertThat(captor.getValue().getTimeLimit()).isEqualTo(25000);
    }

    @Test
    @DisplayName("Group search controls receive the configured timeout when group search is enabled")
    void testAuthenticate_groupSearchControls_receivesConfiguredTimeLimitWhenEnabled() throws Exception {
        config.setConnectionTimeout(35);
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(false);
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();

        ArgumentCaptor<SearchControls> groupCaptor = ArgumentCaptor.forClass(SearchControls.class);
        verify(bindCtx).search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), groupCaptor.capture());
        assertThat(groupCaptor.getValue().getTimeLimit()).isEqualTo(35000);
    }

    @Test
    @DisplayName("Timeout exceptions during search do not leak credentials in response")
    void testAuthenticate_searchTimeout_doesNotLeakCredentials() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class)))
                .thenThrow(new TimeLimitExceededException("LDAP operation timed out"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("LDAP operation timed out");
        assertThat(response.getMessage()).doesNotContain(USER_PASSWORD);
        assertThat(response.getMessage()).doesNotContain(BIND_PASSWORD);
    }

    @Test
    @DisplayName("getValidatedTimeoutMillis throws IllegalArgumentException when config is null")
    void testGetValidatedTimeoutMillis_nullConfig_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ldapService.getValidatedTimeoutMillis(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("LDAP configuration must not be null");
    }

    // ─── Part 3: Group Enumeration Cleanup Tests ──────────────────────

    @Test
    @DisplayName("Group enumeration is closed after successful iteration")
    void testAuthenticate_groupEnumeration_closedAfterSuccessfulIteration() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(true, false);
        SearchResult groupResult = new SearchResult("developers", null, new BasicAttributes());
        groupResult.setNameInNamespace("cn=developers,ou=groups,dc=example,dc=com");
        when(groupResults.next()).thenReturn(groupResult);
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getGroups()).containsExactly("cn=developers,ou=groups,dc=example,dc=com");
        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group enumeration is closed when no group results are found")
    void testAuthenticate_groupEnumeration_closedWhenNoResultsFound() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(false);
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getGroups()).isEmpty();
        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group enumeration is closed when hasMore() throws NamingException")
    void testAuthenticate_groupEnumeration_closedWhenHasMoreThrows() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenThrow(new NamingException("Group stream error"));
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Group stream error");
        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group enumeration is closed when next() throws NamingException")
    void testAuthenticate_groupEnumeration_closedWhenNextThrows() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(true);
        when(groupResults.next()).thenThrow(new NamingException("Entry decoding failed"));
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Entry decoding failed");
        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group enumeration close exception is safely ignored without failing authentication")
    void testAuthenticate_groupEnumeration_closeThrowsHandledSafely() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(true, false);
        SearchResult groupResult = new SearchResult("developers", null, new BasicAttributes());
        groupResult.setNameInNamespace("cn=developers,ou=groups,dc=example,dc=com");
        when(groupResults.next()).thenReturn(groupResult);
        doThrow(new NamingException("Group enumeration close failed")).when(groupResults).close();
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
        assertThat(response.getGroups()).containsExactly("cn=developers,ou=groups,dc=example,dc=com");
        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group search throwing exception before enumeration is created is handled safely")
    void testAuthenticate_groupSearch_searchThrowsBeforeReturningEnumerationHandledSafely() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenThrow(new NamingException("Group search base invalid"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Group search base invalid");
        verify(bindCtx).close();
    }

    // ─── LDAP Filter Escaping & Injection Prevention Tests ───────────

    @Test
    @DisplayName("Normal username 'bikash' produces expected filter and authenticates successfully")
    void testAuthenticate_normalUsername_escapesAndAuthenticatesSuccessfully() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), eq("(sAMAccountName=bikash)"), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("bikash", null, new BasicAttributes());
        result.setNameInNamespace("cn=bikash,ou=users,dc=example,dc=com");
        when(searchResults.next()).thenReturn(result);

        LdapTestResponse response = ldapService.authenticate("bikash", USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
        assertThat(response.getUserDn()).isEqualTo("cn=bikash,ou=users,dc=example,dc=com");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=bikash)");
    }

    @Test
    @DisplayName("Asterisk '*' is escaped as '\\2a' in LDAP search filter")
    void testAuthenticate_escapesAsterisk() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("user*name", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=user\\2aname)");
    }

    @Test
    @DisplayName("Left parenthesis '(' is escaped as '\\28' in LDAP search filter")
    void testAuthenticate_escapesLeftParenthesis() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("user(name", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=user\\28name)");
    }

    @Test
    @DisplayName("Right parenthesis ')' is escaped as '\\29' in LDAP search filter")
    void testAuthenticate_escapesRightParenthesis() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("user)name", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=user\\29name)");
    }

    @Test
    @DisplayName("Backslash '\\' is escaped as '\\5c' in LDAP search filter")
    void testAuthenticate_escapesBackslash() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("user\\name", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=user\\5cname)");
    }

    @Test
    @DisplayName("NUL character '\\0' is escaped as '\\00' in LDAP search filter")
    void testAuthenticate_escapesNulCharacter() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("user\0name", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=user\\00name)");
    }

    @Test
    @DisplayName("Multiple special characters are all escaped in a single username")
    void testAuthenticate_escapesMultipleSpecialCharacters() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("*(\\)\0", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=\\2a\\28\\5c\\29\\00)");
    }

    @Test
    @DisplayName("LDAP injection attempt '*(uid=*)' is sanitized into literal filter value")
    void testAuthenticate_preventsLdapInjection_wildcardAndNewFilterClause() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("*)(uid=*)", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=\\2a\\29\\28uid=\\2a\\29)");
    }

    @Test
    @DisplayName("LDAP injection attempt 'admin)(|(uid=*))' is sanitized and cannot inject OR clause")
    void testAuthenticate_preventsLdapInjection_orConditionBypass() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("admin)(|(uid=*))", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=admin\\29\\28|\\28uid=\\2a\\29\\29)");
    }

    @Test
    @DisplayName("LDAP injection attempt 'admin)(|(password=*))' is sanitized")
    void testAuthenticate_preventsLdapInjection_passwordProbe() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("admin)(|(password=*))", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=admin\\29\\28|\\28password=\\2a\\29\\29)");
    }

    @Test
    @DisplayName("Pure wildcard '*' username is escaped and cannot match all directory entries")
    void testAuthenticate_preventsLdapInjection_pureWildcard() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        ldapService.authenticate("*", USER_PASSWORD, config, BIND_PASSWORD);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=\\2a)");
    }

    @Test
    @DisplayName("Null username is rejected with failure response and bind context is closed")
    void testAuthenticate_nullUsername_rejectedWithFailureResponse() throws Exception {
        LdapTestResponse response = ldapService.authenticate(null, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Username must not be null");
        verify(bindCtx, never()).search(anyString(), anyString(), any(SearchControls.class));
        verify(bindCtx).close();
    }

    @Test
    @DisplayName("Empty string username is treated literally without throwing error")
    void testAuthenticate_emptyUsername_treatedAsLiteral() throws Exception {
        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(false);

        LdapTestResponse response = ldapService.authenticate("", USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("User not found in directory");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(bindCtx).search(eq(config.getUserSearchBase()), captor.capture(), any(SearchControls.class));
        assertThat(captor.getValue()).isEqualTo("(sAMAccountName=)");
    }

    // ─── Regression Tests: User DN Filter Injection & Sensitive Exception Masking ───

    @Test
    @DisplayName("Group search with normal user DN uses parameterized JNDI overload, retrieves groups, and closes enumeration")
    void testAuthenticate_groupSearch_parameterizedWithNormalUserDn_succeedsAndClosesEnumeration() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace("cn=alice,ou=users,dc=example,dc=com");
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(true, false);
        SearchResult groupResult = new SearchResult("engineers", null, new BasicAttributes());
        groupResult.setNameInNamespace("cn=engineers,ou=groups,dc=example,dc=com");
        when(groupResults.next()).thenReturn(groupResult);

        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate("alice", USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo("Authentication successful");
        assertThat(response.getUserDn()).isEqualTo("cn=alice,ou=users,dc=example,dc=com");
        assertThat(response.getGroups()).containsExactly("cn=engineers,ou=groups,dc=example,dc=com");

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(bindCtx).search(eq("ou=groups,dc=example,dc=com"), filterCaptor.capture(), argsCaptor.capture(), any(SearchControls.class));
        assertThat(filterCaptor.getValue()).isEqualTo("(member={0})");
        assertThat(argsCaptor.getValue()).containsExactly("cn=alice,ou=users,dc=example,dc=com");

        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group search with malicious user DN containing *, (, ), and \\ passes DN as separate argument and preserves filter structure")
    void testAuthenticate_groupSearch_maliciousUserDn_doesNotAlterFilterStructure() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        String maliciousUserDn = "cn=admin*)(|(member=*))\\foo,ou=users,dc=example,dc=com";

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(maliciousUserDn);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(false);
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate("alice", USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getUserDn()).isEqualTo(maliciousUserDn);

        ArgumentCaptor<String> filterCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(bindCtx).search(eq("ou=groups,dc=example,dc=com"), filterCaptor.capture(), argsCaptor.capture(), any(SearchControls.class));

        // The filter structure MUST remain strictly "(member={0})" and not expand or inject OR/AND expressions
        assertThat(filterCaptor.getValue()).isEqualTo("(member={0})");
        assertThat(argsCaptor.getValue()).containsExactly(maliciousUserDn);

        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("Group search enumeration is closed when iteration throws an exception with malicious user DN")
    void testAuthenticate_groupSearch_maliciousUserDn_enumerationClosedWhenIterationThrows() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        String maliciousUserDn = "cn=admin*)(|(member=*))\\bar,ou=users,dc=example,dc=com";

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(maliciousUserDn);
        when(searchResults.next()).thenReturn(userResult);

        @SuppressWarnings("unchecked")
        NamingEnumeration<SearchResult> groupResults = mock(NamingEnumeration.class);
        when(groupResults.hasMore()).thenReturn(true);
        when(groupResults.next()).thenThrow(new NamingException("Corrupted group stream entry"));
        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenReturn(groupResults);

        LdapTestResponse response = ldapService.authenticate("alice", USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage()).doesNotContain("Corrupted group stream entry");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();

        verify(groupResults, times(1)).close();
    }

    @Test
    @DisplayName("testConnection sanitizes raw LDAP exception containing internal host, IP, and directory paths")
    void testTestConnection_providerThrowsSensitiveDetails_sanitizesErrorMessageAndMasksFields() throws Exception {
        doAnswer(invocation -> {
            throw new CommunicationException("Connection failed to ldap://ad-internal-01.corp.private:389/dc=corp,dc=internal (Socket closed by remote host 10.100.20.15)");
        }).when(ldapService).createDirContext(any());

        LdapTestResponse response = ldapService.testConnection(config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Connection failed");
        assertThat(response.getMessage())
                .doesNotContain("ad-internal-01")
                .doesNotContain("corp.private")
                .doesNotContain("389")
                .doesNotContain("10.100.20.15")
                .doesNotContain("Socket closed")
                .doesNotContain("CommunicationException")
                .doesNotContain("dc=corp,dc=internal");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();
    }

    @Test
    @DisplayName("testConnection sanitizes invalid configuration exception and returns client-facing message")
    void testTestConnection_invalidConfiguration_returnsSanitizedConfigMessage() throws Exception {
        config.setConnectionTimeout(-1);

        LdapTestResponse response = ldapService.testConnection(config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Invalid LDAP configuration.");
        assertThat(response.getMessage()).doesNotContain("timeout").doesNotContain("-1");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();
        verify(ldapService, never()).createDirContext(any());
    }

    @Test
    @DisplayName("authenticate sanitizes user search exception containing internal host, port, and IP")
    void testAuthenticate_userSearchThrowsSensitiveDetails_sanitizesErrorMessageAndMasksFields() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class)))
                .thenThrow(new CommunicationException("Search failed on ldap://ad-internal-01.corp.private:636/ou=users,dc=corp,dc=internal: Read timed out from 10.100.20.15"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage())
                .doesNotContain("ad-internal-01")
                .doesNotContain("corp.private")
                .doesNotContain("636")
                .doesNotContain("10.100.20.15")
                .doesNotContain("Read timed out")
                .doesNotContain("ou=users");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();
    }

    @Test
    @DisplayName("authenticate sanitizes user bind exception containing Active Directory error codes and server info")
    void testAuthenticate_userBindThrowsSensitiveDetails_sanitizesErrorMessageAndMasksFields() throws Exception {
        when(bindCtx.search(anyString(), anyString(), any(SearchControls.class))).thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult result = new SearchResult("alice", null, new BasicAttributes());
        result.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(result);

        doAnswer(invocation -> {
            Hashtable<String, String> env = invocation.getArgument(0);
            String principal = env.get(Context.SECURITY_PRINCIPAL);
            if (BIND_DN.equals(principal)) {
                return bindCtx;
            } else {
                throw new AuthenticationException("[LDAP: error code 49 - 80180008: LdapErr: DSID-0C090447, comment: AcceptSecurityContext error, data 52e, v3839] on server dc01.internal.corp:389");
            }
        }).when(ldapService).createDirContext(any());

        LdapTestResponse response = ldapService.authenticate(USERNAME, "wrongPassword", config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage())
                .doesNotContain("error code 49")
                .doesNotContain("80180008")
                .doesNotContain("DSID")
                .doesNotContain("AcceptSecurityContext")
                .doesNotContain("52e")
                .doesNotContain("dc01.internal.corp");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();
    }

    @Test
    @DisplayName("authenticate sanitizes group search exception containing internal host and group base")
    void testAuthenticate_groupSearchThrowsSensitiveDetails_sanitizesErrorMessageAndMasksFields() throws Exception {
        config.setGroupSearchBase("ou=groups,dc=example,dc=com");

        when(bindCtx.search(eq(config.getUserSearchBase()), anyString(), any(SearchControls.class)))
                .thenReturn(searchResults);
        when(searchResults.hasMore()).thenReturn(true, false);
        SearchResult userResult = new SearchResult("alice", null, new BasicAttributes());
        userResult.setNameInNamespace(USER_DN);
        when(searchResults.next()).thenReturn(userResult);

        when(bindCtx.search(eq("ou=groups,dc=example,dc=com"), eq("(member={0})"), any(Object[].class), any(SearchControls.class)))
                .thenThrow(new NamingException("Group search failed on ou=groups,dc=internal.corp:389: Server unavailable at 10.100.20.16"));

        LdapTestResponse response = ldapService.authenticate(USERNAME, USER_PASSWORD, config, BIND_PASSWORD);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo("Authentication failed.");
        assertThat(response.getMessage())
                .doesNotContain("ou=groups,dc=internal.corp")
                .doesNotContain("389")
                .doesNotContain("10.100.20.16")
                .doesNotContain("Server unavailable")
                .doesNotContain("Group search failed");
        assertThat(response.getUserDn()).isNull();
        assertThat(response.getGroups()).isEmpty();
    }
}
