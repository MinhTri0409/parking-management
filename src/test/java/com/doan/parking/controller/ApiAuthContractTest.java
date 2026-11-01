package com.doan.parking.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static com.jayway.jsonpath.JsonPath.read;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.mail.host=localhost"
})
@AutoConfigureMockMvc
class ApiAuthContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticationManager authenticationManager;

    @BeforeEach
    void resetAuthenticationManager() {
        org.mockito.Mockito.reset(authenticationManager);
    }

    @Test
    void loginPreservesAdminAuthority() throws Exception {
        mockAuthenticated("admin", "ROLE_ADMIN");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.redirectTo").value("/admin"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"));
    }

    @Test
    void publicHomeIncludesOnlySafeParkingSlotStatuses() throws Exception {
        mockMvc.perform(get("/api/public/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parkingSections").isArray())
                .andExpect(jsonPath("$.parkingSections[0].slots").isArray());
    }

    @Test
    void loginPreservesStaffAndCustomerAuthorities() throws Exception {
        mockAuthenticated("staff", "ROLE_STAFF");
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"staff\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_STAFF"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.redirectTo").value("/admin"));

        mockAuthenticated("customer", "ROLE_CUSTOMER");
        mockMvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"username\":\"customer\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_CUSTOMER"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.redirectTo").value("/dashboard"));
    }

    @Test
    void loginPreservesMixedAuthorityArrayAndUsesPriority() throws Exception {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "mixed", "secret", List.of(
                        new SimpleGrantedAuthority("ROLE_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"mixed\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_STAFF"))
                .andExpect(jsonPath("$.authorities[1]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.authorities[2]").value("ROLE_CUSTOMER"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.redirectTo").value("/dashboard"));

        authentication = new UsernamePasswordAuthenticationToken(
                "mixed-admin", "secret", List.of(
                        new SimpleGrantedAuthority("ROLE_STAFF"),
                        new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"mixed-admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.redirectTo").value("/admin"));
    }

    @Test
    void loginReturnsGenericInvalidCredentialsContract() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new BadCredentialsException("bad credentials"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"missing\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.role").value(nullValue()));
    }

    @Test
    void sessionEndpointReadsSecurityContextAndReturnsExactAuthorities() throws Exception {
        mockMvc.perform(get("/api/auth/session")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.redirectTo").value("/admin"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty());
    }

    @Test
    void anonymousSessionAndCsrfContractsAreStable() throws Exception {
        mockMvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.authorities").isEmpty())
                .andExpect(jsonPath("$.role").value(nullValue()))
                .andExpect(jsonPath("$.redirectTo").value(nullValue()))
                .andExpect(jsonPath("$.csrf").value(nullValue()));

        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginIsCsrfExemptButLogoutRequiresTheRotatedSessionToken() throws Exception {
        MockHttpSession bootstrapSession = new MockHttpSession();
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf")
                        .session(bootstrapSession))
                .andExpect(status().isOk())
                .andReturn();
        String bootstrapToken = read(bootstrap.getResponse().getContentAsString(), "$.token");

        mockAuthenticated("admin", "ROLE_ADMIN");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(bootstrapSession)
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession loginSession = (MockHttpSession) login.getRequest().getSession(false);
        String loginToken = read(login.getResponse().getContentAsString(), "$.csrf.token");
        assertNotEquals(bootstrapToken, loginToken);

        mockMvc.perform(post("/api/auth/logout")
                        .session(loginSession)
                        .header("X-CSRF-TOKEN", bootstrapToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/logout")
                        .session(loginSession)
                        .header("X-CSRF-TOKEN", loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));

        mockMvc.perform(get("/api/auth/session")
                        .session(loginSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void loginPreservesUnknownAuthorityAndReturnsNullNavigationConvenience() throws Exception {
        mockAuthenticated("auditor", "ROLE_AUDITOR");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"auditor\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_AUDITOR"))
                .andExpect(jsonPath("$.role").value(nullValue()))
                .andExpect(jsonPath("$.redirectTo").value(nullValue()));
    }

    @Test
    void disabledAccountUsesTheSameGenericInvalidCredentialsContract() throws Exception {
        when(authenticationManager.authenticate(any())).thenThrow(new DisabledException("disabled"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"disabled\",\"password\":\"secret\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Tên đăng nhập hoặc mật khẩu không đúng"));
    }

    @Test
    void loginRotatesCsrfTokenAndSessionIdentity() throws Exception {
        MockHttpSession oldSession = new MockHttpSession();
        MvcResult bootstrap = mockMvc.perform(get("/api/auth/csrf").session(oldSession))
                .andExpect(status().isOk())
                .andReturn();
        String oldToken = read(bootstrap.getResponse().getContentAsString(), "$.token");
        String oldSessionId = oldSession.getId();

        mockAuthenticated("admin", "ROLE_ADMIN");
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(oldSession)
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String newToken = read(login.getResponse().getContentAsString(), "$.csrf.token");
        String newSessionId = login.getRequest().getSession(false).getId();

        assertNotEquals(oldToken, newToken);
        assertNotEquals(oldSessionId, newSessionId);
    }

    @Test
    void customerCannotAccessAdminRoute() throws Exception {
        mockMvc.perform(get("/admin/users")
                        .with(user("customer").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedMutationWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/admin/pricing/1")
                        .with(user("staff").roles("STAFF")))
                .andExpect(status().isForbidden());
    }

    private void mockAuthenticated(String username, String authority) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username, "secret", List.of(new SimpleGrantedAuthority(authority)));
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
    }
}
