package com.doan.parking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/** Cấu hình bảo mật: đăng nhập, đăng xuất và phân quyền ADMIN / STAFF. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    // Quy định trang nào ai được truy cập
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(new AntPathRequestMatcher("/api/auth/login", HttpMethod.POST.name())))
            .authorizeHttpRequests(a -> a
                // Tài nguyên giao diện phải tải được trước khi người dùng đăng nhập
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico").permitAll()
                // API dùng bởi Next.js và các trang công khai
                .requestMatchers("/api/public/**", "/api/auth/**").permitAll()
                .requestMatchers("/api/customer/**", "/api/dashboard", "/api/reservations/payment/qr")
                    .hasRole("CUSTOMER")
                .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "STAFF")
                // Trang công khai không cần đăng nhập
                .requestMatchers("/", "/login", "/register").permitAll()
                .requestMatchers("/contact", "/contact/**").permitAll()
                .requestMatchers("/customer/account").hasRole("CUSTOMER")
                .requestMatchers("/customer/notifications/**").hasRole("CUSTOMER")
                .requestMatchers("/customer/support/**").hasRole("CUSTOMER")
                .requestMatchers("/admin/support/**").hasRole("ADMIN")
                .requestMatchers("/support/messages/*/image").hasAnyRole("ADMIN", "CUSTOMER")
                .requestMatchers("/dashboard").hasRole("CUSTOMER")
                .requestMatchers("/staff/dashboard").hasAnyRole("ADMIN", "STAFF")
                .requestMatchers("/admin/reservations", "/admin/reservations/**")
                .hasAnyRole("ADMIN", "STAFF")
                .requestMatchers("/reservations").hasRole("CUSTOMER")
                .requestMatchers("/reservations/**").hasRole("CUSTOMER")
                // Chỉ ADMIN được vào trang tài khoản, bảng giá và báo cáo
                .requestMatchers(HttpMethod.POST, "/users/*/phone").hasAnyRole("ADMIN", "STAFF", "CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/admin/users/*/phone").hasAnyRole("ADMIN", "STAFF")
                .requestMatchers("/admin/users/**", "/admin/pricing/**", "/admin/reports/**").hasRole("ADMIN")
                // Chỉ ADMIN được chỉnh sửa, hủy hoặc xóa vé tháng
                .requestMatchers(HttpMethod.POST,
                        "/admin/tickets/*/delete", "/admin/tickets/*/rename",
                        "/admin/tickets/*/cancel", "/admin/tickets/*/cancel-renewal").hasRole("ADMIN")
                .requestMatchers("/admin/**").hasAnyRole("ADMIN", "STAFF")
                .requestMatchers("/users/**").hasRole("ADMIN")
                // Các trang còn lại yêu cầu phải đăng nhập
                .anyRequest().hasAnyRole("ADMIN", "STAFF"))
            .formLogin(f -> f.loginPage("/login").successHandler((request, response, authentication) -> {
                boolean customer = authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_CUSTOMER"));
                boolean admin = authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
                boolean staff = authentication.getAuthorities().stream()
                        .anyMatch(authority -> authority.getAuthority().equals("ROLE_STAFF"));
                String redirect = customer ? "/dashboard" : (admin || staff ? "/admin" : "/login?error");
                response.sendRedirect(redirect);
            }).permitAll())
            // Đăng xuất xong quay lại trang đăng nhập; API logout cũng trả JSON và xóa JSESSIONID
            .logout(l -> l
                    .logoutRequestMatcher(new OrRequestMatcher(
                            new AntPathRequestMatcher("/logout", HttpMethod.POST.name()),
                            new AntPathRequestMatcher("/api/auth/logout", HttpMethod.POST.name())))
                    .addLogoutHandler(new CookieClearingLogoutHandler("JSESSIONID"))
                    .logoutSuccessHandler((request, response, authentication) -> {
                        if (request.getRequestURI().equals("/api/auth/logout")) {
                            response.setStatus(200);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write("{\"authenticated\":false,\"username\":null,\"authorities\":[],\"role\":null,\"redirectTo\":null,\"csrf\":null}");
                        } else {
                            response.sendRedirect("/login?logout");
                        }
                    }));
        return http.build();
    }

    @Bean
    public CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    // Dùng BCrypt để mã hóa mật khẩu
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
