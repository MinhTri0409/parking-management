package com.doan.parking.controller;

import com.doan.parking.entity.Pricing;
import com.doan.parking.entity.VehicleType;
import com.doan.parking.repository.PricingRepository;
import com.doan.parking.service.ParkingService;
import com.doan.parking.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiController {
    private final PricingRepository pricingRepository;
    private final ParkingService parkingService;
    private final ReservationService reservationService;
    private final AuthenticationManager authenticationManager;
    private final CsrfTokenRepository csrfTokenRepository;

    @GetMapping("/public/home")
    public Map<String, Object> publicHome() {
        List<ParkingService.TypeStat> occupancy = parkingService.occupancy();
        List<Map<String, Object>> parkingSections = parkingService.parkingMap().stream().map(section -> {
            List<Map<String, String>> slots = section.slots().stream().map(slot -> {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("code", slot.code());
                item.put("state", slot.occupied() ? "OCCUPIED" : slot.reserved() ? "RESERVED" : "AVAILABLE");
                return item;
            }).toList();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", section.type().name());
            item.put("label", section.type().getLabel());
            item.put("capacity", section.stats().capacity());
            item.put("used", section.stats().used());
            item.put("slots", slots);
            return item;
        }).toList();
        LocalDateTime now = LocalDateTime.now().plusSeconds(1);
        long totalCapacity = occupancy.stream().mapToLong(ParkingService.TypeStat::capacity).sum();
        long availableSpaces = java.util.Arrays.stream(VehicleType.values())
                .mapToLong(type -> reservationService.availableSlots(type, now, now.plusHours(1)).stream()
                        .filter(ReservationService.SlotOption::available).count())
                .sum();

        List<Map<String, Object>> prices = pricingRepository.findAll().stream().map(price -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("vehicleType", price.getVehicleType().name());
            item.put("turnPrice", price.getTurnPrice());
            item.put("monthPrice", price.getMonthPrice());
            item.put("capacity", price.getCapacity());
            item.put("reservationHourlyPrice", price.getReservationHourlyPrice());
            return item;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("totalCapacity", totalCapacity);
        response.put("availableSpaces", availableSpaces);
        response.put("prices", prices);
        response.put("parkingSections", parkingSections);
        return response;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(body.get("username"), body.get("password")));

            HttpSession previousSession = request.getSession(false);
            if (previousSession != null) {
                request.changeSessionId();
            }
            HttpSession session = request.getSession(true);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            csrfTokenRepository.saveToken(null, request, response);
            CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);

            List<String> authorities = authentication.getAuthorities().stream()
                    .map(authority -> authority.getAuthority())
                    .toList();
            RoleRedirect roleRedirect = deriveRoleRedirect(authorities);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("authenticated", true);
            result.put("username", authentication.getName());
            result.put("authorities", authorities);
            result.put("role", roleRedirect.role());
            result.put("redirectTo", roleRedirect.redirectTo());
            result.put("csrf", csrfJson(csrfToken));
            return ResponseEntity.ok(result);
        } catch (AuthenticationException ex) {
            return invalidCredentials();
        }
    }

    @GetMapping("/auth/session")
    public Map<String, Object> session(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return anonymousSession();
        }

        List<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
        RoleRedirect roleRedirect = deriveRoleRedirect(authorities);

        CsrfToken csrfToken = csrfTokenRepository.loadToken(request);
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", true);
        result.put("username", authentication.getName());
        result.put("authorities", authorities);
        result.put("role", roleRedirect.role());
        result.put("redirectTo", roleRedirect.redirectTo());
        result.put("csrf", csrfJson(csrfToken));
        return result;
    }

    @GetMapping("/auth/csrf")
    public Map<String, Object> csrf(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken csrfToken = csrfTokenRepository.loadToken(request);
        if (csrfToken == null) {
            csrfToken = csrfTokenRepository.generateToken(request);
            csrfTokenRepository.saveToken(csrfToken, request, response);
        }
        return csrfJson(csrfToken);
    }

    private ResponseEntity<Map<String, Object>> invalidCredentials() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", false);
        result.put("username", null);
        result.put("authorities", List.of());
        result.put("role", null);
        result.put("redirectTo", null);
        result.put("code", "INVALID_CREDENTIALS");
        result.put("message", "Tên đăng nhập hoặc mật khẩu không đúng");
        result.put("csrf", null);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(result);
    }

    private Map<String, Object> anonymousSession() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authenticated", false);
        result.put("username", null);
        result.put("authorities", List.of());
        result.put("role", null);
        result.put("redirectTo", null);
        result.put("csrf", null);
        return result;
    }

    private Map<String, Object> csrfJson(CsrfToken token) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headerName", token.getHeaderName());
        result.put("parameterName", token.getParameterName());
        result.put("token", token.getToken());
        return result;
    }

    static RoleRedirect deriveRoleRedirect(List<String> authorities) {
        if (authorities.contains("ROLE_CUSTOMER")) {
            return new RoleRedirect("CUSTOMER", "/dashboard");
        }
        if (authorities.contains("ROLE_ADMIN")) {
            return new RoleRedirect("ADMIN", "/admin");
        }
        if (authorities.contains("ROLE_STAFF")) {
            return new RoleRedirect("STAFF", "/admin");
        }
        return new RoleRedirect(null, null);
    }

    record RoleRedirect(String role, String redirectTo) {}
}
