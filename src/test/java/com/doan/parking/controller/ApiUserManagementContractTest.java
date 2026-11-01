package com.doan.parking.controller;

import com.doan.parking.entity.AppUser;
import com.doan.parking.repository.ContactInquiryRepository;
import com.doan.parking.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api-user-contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.mail.host=",
        "spring.mail.username=",
        "app.contact.from="
})
@AutoConfigureMockMvc
@Transactional
class ApiUserManagementContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContactInquiryRepository contactInquiryRepository;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
        contactInquiryRepository.deleteAll();
        saveUser("operator", "STAFF");
    }

    @Test
    void contactInquiryIsSavedWhenEmailIsNotConfigured() throws Exception {
        mockMvc.perform(post("/api/public/contact")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Nguyen Van A\",\"phone\":\"0862282415\",\"email\":\"a@example.com\",\"customerType\":\"NEW\",\"message\":\"Toi can hoi ve dich vu gui xe.\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.saved").value(true))
                .andExpect(jsonPath("$.sent").value(false));

        assertEquals(1, contactInquiryRepository.count());
        var savedInquiry = contactInquiryRepository.findAll().get(0);
        assertEquals("a@example.com", savedInquiry.getEmail());
        assertEquals("FAILED", savedInquiry.getEmailStatus());
    }

    @Test
    void adminCannotCreateCustomerThroughStaffAccountForm() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .with(user("operator").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"new-customer\",\"fullName\":\"Customer\",\"password\":\"secret1\",\"role\":\"CUSTOMER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registrationPhoneIsReturnedInCustomerAccountProfile() throws Exception {
        mockMvc.perform(post("/api/public/register")
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"phone-customer\",\"fullName\":\"Phone Customer\",\"phone\":\"0862282415\",\"password\":\"secret123\",\"confirmPassword\":\"secret123\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/customer/account")
                        .with(user("phone-customer").roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0862282415"));
    }

    @Test
    void lastActiveAdminCannotBeDeletedOrDisabled() throws Exception {
        AppUser admin = saveUser("sole-admin", "ADMIN");
        mockMvc.perform(delete("/api/admin/users/{id}", admin.getId())
                        .with(user("operator").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/users/{id}/toggle", admin.getId())
                        .with(user("operator").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotDeleteOrDisableTheirOwnAccount() throws Exception {
        AppUser admin = saveUser("current-admin", "ADMIN");
        mockMvc.perform(delete("/api/admin/users/{id}", admin.getId())
                        .with(user("current-admin").roles("ADMIN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(put("/api/admin/users/{id}/toggle", admin.getId())
                        .with(user("current-admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void passwordChangeRequiresMatchingConfirmation() throws Exception {
        AppUser staff = saveUser("staff-user", "STAFF");
        mockMvc.perform(put("/api/admin/users/{id}/password", staff.getId())
                        .with(user("operator").roles("ADMIN"))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content("{\"newPassword\":\"new-secret\",\"confirmPassword\":\"different-secret\"}"))
                .andExpect(status().isBadRequest());
    }

    private AppUser saveUser(String username, String role) {
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPassword("encoded-password");
        user.setFullName(username);
        user.setRole(role);
        user.setEnabled(true);
        return userRepository.save(user);
    }
}
