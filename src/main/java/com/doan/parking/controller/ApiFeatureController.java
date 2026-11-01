package com.doan.parking.controller;

import com.doan.parking.entity.*;
import com.doan.parking.repository.*;
import com.doan.parking.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;
import java.util.Base64;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApiFeatureController {
    private final PricingRepository pricingRepository;
    private final ParkingService parkingService;
    private final ReservationService reservationService;
    private final UserRepository userRepository;
    private final ParkingRecordRepository parkingRecordRepository;
    private final ParkingRecordPhotosRepository parkingRecordPhotosRepository;
    private final MonthlyTicketRepository ticketRepository;
    private final CustomerNotificationService notificationService;
    private final SupportChatService supportChatService;
    private final ReportService reportService;
    private final ContactInquiryService contactInquiryService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping("/public/prices")
    public List<Map<String,Object>> publicPrices() {
        return pricingRepository.findAll().stream().map(this::pricing).toList();
    }

    @PostMapping("/public/contact")
    public ResponseEntity<Map<String,Object>> contact(@RequestBody Map<String,String> body) {
        String name = text(body, "name");
        String phone = text(body, "phone");
        String email = text(body, "email");
        String customerType = text(body, "customerType");
        String message = text(body, "message");
        Pattern emailPattern = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
        Pattern phonePattern = Pattern.compile("^[0-9+().\\- ]{7,20}$");
        if (name.isBlank() || name.length() > 100 || name.contains("\n") || name.contains("\r")
                || phone.isBlank() || !phonePattern.matcher(phone).matches()
                || email.length() > 254 || !emailPattern.matcher(email).matches()
                || !("NEW".equals(customerType) || "EXISTING".equals(customerType))
                || message.length() < 10 || message.length() > 5000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Thông tin liên hệ không hợp lệ."));
        }
        boolean sent = contactInquiryService.saveAndSend(name, phone, email, customerType, message);
        String statusMessage = sent
                ? "Lời nhắn đã được lưu và gửi đến nhà xe. Cảm ơn bạn!"
                : "Lời nhắn đã được lưu vào hệ thống nhưng chưa gửi được email. Vui lòng liên hệ trực tiếp nếu cần gấp.";
        return ResponseEntity.accepted().body(Map.of("saved", true, "sent", sent, "message", statusMessage));
    }

    @PostMapping("/public/register")
    public ResponseEntity<Map<String,Object>> register(@RequestBody Map<String,String> body) {
        String username = text(body,"username"); String fullName = text(body,"fullName");
        String phone = text(body,"phone");
        String password = text(body,"password"); String confirm = text(body,"confirmPassword");
        Pattern phonePattern = Pattern.compile("^[0-9+().\\- ]{7,20}$");
        if (!username.matches("[A-Za-z0-9._-]{3,50}")) return ResponseEntity.badRequest().body(Map.of("error","Tên đăng nhập không hợp lệ."));
        if (fullName.isBlank() || fullName.length() > 100) return ResponseEntity.badRequest().body(Map.of("error","Họ tên không hợp lệ."));
        if (!phonePattern.matcher(phone).matches()) return ResponseEntity.badRequest().body(Map.of("error","Số điện thoại không hợp lệ."));
        if (password.length() < 8) return ResponseEntity.badRequest().body(Map.of("error","Mật khẩu cần ít nhất 8 ký tự."));
        if (!password.equals(confirm)) return ResponseEntity.badRequest().body(Map.of("error","Mật khẩu xác nhận không khớp."));
        if (userRepository.findByUsername(username).isPresent()) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Tên đăng nhập đã được sử dụng."));
        AppUser customer = new AppUser(); customer.setUsername(username); customer.setFullName(fullName); customer.setPhone(phone.trim());
        customer.setPassword(passwordEncoder.encode(password)); customer.setRole("CUSTOMER"); customer.setEnabled(true);
        AppUser saved = userRepository.save(customer);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id",saved.getId(),"username",saved.getUsername(),"role","CUSTOMER"));
    }

    @GetMapping("/dashboard") @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String,Object> customerDashboard(Principal principal) {
        List<ParkingReservation> bookings = reservationService.customerBookings(principal.getName());
        Map<String,Object> result = new LinkedHashMap<>(); result.put("reservations", bookings.stream().map(this::reservation).toList());
        result.put("totalBookings", bookings.size());
        result.put("pendingPaymentCount", bookings.stream().filter(r -> r.getStatus()==ReservationStatus.PENDING_PAYMENT).count());
        result.put("parkingSections", parkingService.parkingMap());
        return result;
    }

    @GetMapping("/customer/account") @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String,Object> account(Principal principal) {
        AppUser user = userRepository.findByUsername(principal.getName()).orElseThrow(); return userSafe(user);
    }

    @PutMapping("/customer/account/phone") @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String,Object> accountPhone(@RequestBody Map<String,String> body, Principal principal) {
        AppUser user = userRepository.findByUsername(principal.getName()).orElseThrow();
        String phone = text(body,"phone"); user.setPhone(phone.isBlank()?null:phone); userRepository.save(user); return userSafe(user);
    }

    @GetMapping("/customer/reservations") @PreAuthorize("hasRole('CUSTOMER')")
    public Map<String,Object> customerReservations(@RequestParam(required=false) VehicleType type,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) LocalDateTime end, Principal principal) {
        VehicleType selected = type==null?VehicleType.CAR:type; LocalDateTime from=start==null?LocalDateTime.now().plusMinutes(30):start;
        LocalDateTime to=end==null?from.plusHours(2):end;
        Map<String,Object> result=new LinkedHashMap<>();
        try {
            result.put("slots", reservationService.availableSlots(selected, from, to));
            result.put("billableHours", reservationService.billableHours(from, to));
            result.put("estimatedAmount", reservationService.quote(selected, from, to));
        } catch(RuntimeException ex) {
            result.put("slots", List.of());
            result.put("billableHours", 0);
            result.put("estimatedAmount", 0);
            result.put("error", ex.getMessage());
        }
        result.put("reservations",reservationService.customerBookings(principal.getName()).stream().map(this::reservation).toList());
        result.put("hourlyRate",reservationService.hourlyRate(selected)); return result;
    }

    @PostMapping("/customer/reservations") @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<?> createReservation(@RequestBody Map<String,Object> body, Principal principal) {
        try { VehicleType type=VehicleType.valueOf(text(body,"type")); LocalDateTime start=LocalDateTime.parse(text(body,"start")); LocalDateTime end=LocalDateTime.parse(text(body,"end"));
            ParkingReservation r=reservationService.create(principal.getName(),text(body,"plate"),type,text(body,"slotCode"),start,end);
            return ResponseEntity.status(HttpStatus.CREATED).body(reservation(r)); }
        catch(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()==null?"Không thể tạo đặt chỗ":ex.getMessage())); }
    }

    @PostMapping("/customer/reservations/{id}/cancel") @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<?> cancelReservation(@PathVariable Long id, Principal principal) { try { reservationService.cancel(id,principal.getName()); return ResponseEntity.noContent().build(); } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage())); } }
    @GetMapping("/customer/notifications") @PreAuthorize("hasRole('CUSTOMER')") public List<Map<String,Object>> notifications(Principal principal) { return notificationService.pendingFor(principal.getName()).stream().map(n->Map.<String,Object>of("id",n.getId(),"message",n.getMessage())).toList(); }
    @PostMapping("/customer/notifications/{id}/acknowledge") @PreAuthorize("hasRole('CUSTOMER')") public ResponseEntity<Void> acknowledge(@PathVariable Long id,Principal principal) { return notificationService.acknowledge(id,principal.getName())?ResponseEntity.noContent().build():ResponseEntity.notFound().build(); }
    @GetMapping("/customer/notifications/stream") @PreAuthorize("hasRole('CUSTOMER')") public SseEmitter notificationStream(Principal principal) { return notificationService.subscribe(principal.getName()); }

    @GetMapping("/customer/support/messages") @PreAuthorize("hasRole('CUSTOMER')") public List<SupportChatService.MessageResponse> supportMessages(Principal principal) { return supportChatService.customerMessages(principal.getName()); }
    @PostMapping("/customer/support/messages") @PreAuthorize("hasRole('CUSTOMER')") public ResponseEntity<?> sendSupport(@RequestBody Map<String,String> body,Principal principal) { String message=text(body,"message"); if(message.isBlank()||message.length()>2000)return ResponseEntity.badRequest().body(Map.of("error","Tin nhắn không hợp lệ.")); try{return ResponseEntity.ok(supportChatService.sendFromCustomer(principal.getName(),message));}catch(IllegalStateException ex){return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error",ex.getMessage()));} }
    @GetMapping("/customer/support/stream") @PreAuthorize("hasRole('CUSTOMER')") public SseEmitter supportStream(Principal principal) { return supportChatService.subscribeCustomer(principal.getName()); }

    @GetMapping("/admin/dashboard") @PreAuthorize("hasAnyRole('ADMIN','STAFF')")
    public Map<String,Object> staffDashboard() { List<ParkingService.ParkingSection> sections=parkingService.parkingMap(); Map<String,Object> result=new LinkedHashMap<>(); result.put("parkingSections",sections); result.put("occupiedCount",sections.stream().mapToLong(s->s.stats().used()).sum()); result.put("availableCount",sections.stream().flatMap(s->s.slots().stream()).filter(s->!s.occupied()&&!s.reserved()).count()); result.put("totalCapacity",sections.stream().mapToLong(s->s.stats().capacity()).sum()); result.put("todayCheckIns",parkingService.todayCheckIns()); result.put("todayRevenue",parkingService.todayRevenue()); return result; }

    @GetMapping("/admin/parking") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public List<Map<String,Object>> parkingMap() { return parkingService.parkingMap().stream().map(this::parkingSection).toList(); }
    @PostMapping("/admin/parking/checkin") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<?> checkIn(@RequestBody Map<String,String> body,Principal principal) { try { byte[] frontPhoto=vehiclePhoto(body,"frontPhoto"); byte[] platePhoto=vehiclePhoto(body,"platePhoto"); ParkingRecord r=parkingService.checkIn(text(body,"plate"),VehicleType.valueOf(text(body,"type")),principal.getName(),blankToNull(body.get("slotCode")),frontPhoto,platePhoto); return ResponseEntity.ok(record(r)); } catch(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()==null?"Không thể tiếp nhận xe":ex.getMessage())); } }
    @PostMapping("/admin/parking/checkout/{id}/quote") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<?> checkoutQuote(@PathVariable Long id) { try { ParkingService.CheckoutQuote q=parkingService.checkoutQuote(id); ParkingRecordPhotos photos=parkingRecordPhotosRepository.findById(id).orElse(null); Map<String,Object> photoAvailability=Map.of("front",photos!=null&&photos.getFrontPhoto()!=null,"plate",photos!=null&&photos.getPlatePhoto()!=null); Map<String,Object> result=new LinkedHashMap<>(); result.put("record",record(q.record())); result.put("photos",photoAvailability); result.put("amount",q.amount()); result.put("prepaidReservation",q.prepaidReservation()); result.put("bookingCode",q.bookingCode()); result.put("reservedUntil",q.reservedUntil()); result.put("extraHours",q.extraHours()); result.put("hourlyRate",q.hourlyRate()); return ResponseEntity.ok(result); } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage())); } }
    @GetMapping(value="/admin/parking/{id}/photos/{kind}",produces=MediaType.IMAGE_JPEG_VALUE) @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<byte[]> parkingPhoto(@PathVariable Long id,@PathVariable String kind) { ParkingRecordPhotos photos=parkingRecordPhotosRepository.findById(id).orElse(null); if(photos==null)return ResponseEntity.notFound().build(); byte[] image=switch(kind){case "front"->photos.getFrontPhoto();case "plate"->photos.getPlatePhoto();default->null;}; if(image==null)return ResponseEntity.notFound().build(); return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(image); }
    @PostMapping("/admin/parking/checkout/{id}") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<?> checkout(@PathVariable Long id,@RequestBody(required=false) Map<String,Object> body,Principal principal) { try { ParkingService.CheckoutQuote quote=parkingService.checkoutQuote(id); ParkingRecord current=quote.record(); PaymentMethod method=null; if(body!=null&&body.get("paymentMethod")!=null)method=PaymentMethod.valueOf(body.get("paymentMethod").toString()); boolean needsPayment=quote.prepaidReservation()?quote.amount()>0:!current.isMonthlyTicket(); boolean previouslyPaid=method==PaymentMethod.ALREADY_PAID; if(needsPayment&&!previouslyPaid&&(body==null||!Boolean.TRUE.equals(body.get("paymentVerified"))||method==null))return ResponseEntity.badRequest().body(Map.of("error","Vui lòng xác nhận đã hoàn tất thanh toán theo phương thức đã chọn.")); ParkingRecord r=parkingService.checkOut(id,principal.getName(),method); return ResponseEntity.ok(record(r)); } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage())); } }
    @PostMapping("/admin/parking/checkout-overflow-monthly") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public Map<String,Object> checkoutOverflow(Principal principal) { return Map.of("checkedOutCount",parkingService.checkOutOverflowMonthlyTickets(principal.getName())); }
    @GetMapping("/admin/parking/history") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public Map<String,Object> history(@RequestParam(defaultValue="")String plate,@RequestParam(required=false)VehicleType type,@RequestParam(required=false)String checkoutEmployee,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to,@RequestParam(defaultValue="0")int page,Authentication authentication) { boolean admin=authentication.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")); if(!admin)checkoutEmployee=null; Map<String,Object> result=new LinkedHashMap<>(); result.put("page",parkingService.search(plate,type,checkoutEmployee,from,to,page)); result.put("monthlyTickets",plate.isBlank()?List.of():ticketRepository.findByLicensePlateContainingIgnoreCaseOrderByEndDateDesc(plate.trim()).stream().map(this::ticket).toList()); return result; }
    @GetMapping(value="/admin/parking/payment/qr",produces=MediaType.IMAGE_JPEG_VALUE) public ResponseEntity<Resource> paymentQr() { return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore()).body(new ClassPathResource("payment/mb-bank-qr.jpg")); }
    @GetMapping(value="/reservations/payment/qr",produces=MediaType.IMAGE_JPEG_VALUE) @PreAuthorize("hasRole('CUSTOMER')") public ResponseEntity<Resource> reservationQr() { return paymentQr(); }

    @GetMapping("/admin/tickets") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public List<Map<String,Object>> tickets(@RequestParam(defaultValue="")String q) { return ticketRepository.findByLicensePlateContainingIgnoreCaseOrderByEndDateDesc(q.trim()).stream().map(this::ticket).toList(); }
    @PostMapping("/admin/tickets") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<?> addTicket(@RequestBody Map<String,Object> body) { try { String plate=ParkingService.normalize(text(body,"licensePlate")); String owner=text(body,"ownerName"); int months=Integer.parseInt(text(body,"months","1")); VehicleType type=VehicleType.valueOf(text(body,"vehicleType")); if(plate.isEmpty()||plate.length()>20||owner.isBlank()||owner.length()>100||months<1||months>24)return ResponseEntity.badRequest().body(Map.of("error","Thông tin vé tháng không hợp lệ.")); LocalDate today=LocalDate.now(); if(ticketRepository.existsByLicensePlateIgnoreCaseAndVehicleTypeAndStartDateLessThanEqualAndEndDateGreaterThanEqualAndCancelledAtIsNull(plate,type,today.plusMonths(months),today))return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Xe đã có vé tháng cùng loại còn hạn.")); Pricing p=pricingRepository.findByVehicleType(type).orElseThrow(); MonthlyTicket t=new MonthlyTicket(); t.setLicensePlate(plate);t.setOwnerName(owner);t.setPhone(text(body,"phone"));t.setVehicleType(type);t.setStartDate(today);t.setEndDate(today.plusMonths(months));t.setPrice(p.getMonthPrice()*months); return ResponseEntity.status(HttpStatus.CREATED).body(ticket(ticketRepository.save(t))); } catch(RuntimeException ex) { return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()==null?"Không thể đăng ký vé tháng":ex.getMessage())); } }
    @PostMapping("/admin/tickets/{id}/cancel") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> cancelTicket(@PathVariable Long id) { MonthlyTicket t=ticketRepository.findById(id).orElse(null); if(t==null)return ResponseEntity.notFound().build(); if(t.getCancelledAt()!=null)return ResponseEntity.badRequest().body(Map.of("error","Vé tháng này đã được hủy.")); if(t.getStartDate()==null||t.getEndDate()==null)return ResponseEntity.badRequest().body(Map.of("error","Vé tháng không hợp lệ.")); LocalDate today=LocalDate.now(); long totalDays=ChronoUnit.DAYS.between(t.getStartDate(),t.getEndDate())+1; LocalDate start=today.isBefore(t.getStartDate())?t.getStartDate():today; long remaining=today.isAfter(t.getEndDate())?0:ChronoUnit.DAYS.between(start,t.getEndDate())+1; long refund=BigDecimal.valueOf(t.getPrice()).multiply(BigDecimal.valueOf(remaining)).divide(BigDecimal.valueOf(totalDays),0,RoundingMode.HALF_UP).longValueExact(); t.setRenewalCancelled(true);t.setCancelledAt(LocalDateTime.now());t.setRefundAmount(refund); return ResponseEntity.ok(ticket(ticketRepository.save(t))); }
    @PostMapping("/admin/tickets/{id}/rename") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> renameTicket(@PathVariable Long id,@RequestBody Map<String,String> body) { MonthlyTicket t=ticketRepository.findById(id).orElse(null);if(t==null)return ResponseEntity.notFound().build();String plate=ParkingService.normalize(text(body,"licensePlate"));String owner=text(body,"ownerName");if(plate.isEmpty()||plate.length()>20||owner.isBlank()||owner.length()>100)return ResponseEntity.badRequest().body(Map.of("error","Thông tin vé tháng không hợp lệ."));t.setLicensePlate(plate);t.setOwnerName(owner);return ResponseEntity.ok(ticket(ticketRepository.save(t))); }
    @PostMapping("/admin/tickets/{id}/cancel-renewal") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> cancelRenewal(@PathVariable Long id) { MonthlyTicket t=ticketRepository.findById(id).orElse(null);if(t==null)return ResponseEntity.notFound().build();if(t.getCancelledAt()!=null||t.isRenewalCancelled())return ResponseEntity.badRequest().body(Map.of("error","Trạng thái vé tháng không cho phép ngừng gia hạn."));t.setRenewalCancelled(true);return ResponseEntity.ok(ticket(ticketRepository.save(t))); }
    @DeleteMapping("/admin/tickets/{id}") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<Void> deleteTicket(@PathVariable Long id) { if(!ticketRepository.existsById(id))return ResponseEntity.notFound().build(); ticketRepository.deleteById(id);return ResponseEntity.noContent().build(); }

    @GetMapping("/admin/users") @PreAuthorize("hasRole('ADMIN')") public List<Map<String,Object>> users() { return userRepository.findAll().stream().map(this::userSafe).toList(); }
    @PostMapping("/admin/users") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> addUser(@RequestBody Map<String,String> body) {
        String username=text(body,"username").trim();String role=text(body,"role");String password=text(body,"password");String fullName=text(body,"fullName").trim();
        if(username.isBlank()||username.length()>50||password.length()<6||fullName.length()>100||!(role.equals("ADMIN")||role.equals("STAFF")))
            return ResponseEntity.badRequest().body(Map.of("error","Thông tin tài khoản không hợp lệ."));
        if(userRepository.findByUsername(username).isPresent())return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Tên đăng nhập đã tồn tại."));
        AppUser user=new AppUser();user.setUsername(username);user.setFullName(fullName);user.setPassword(passwordEncoder.encode(password));user.setRole(role);user.setEnabled(true);
        return ResponseEntity.status(HttpStatus.CREATED).body(userSafe(userRepository.save(user)));
    }
    @PutMapping("/admin/users/{id}/password") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> changePassword(@PathVariable Long id,@RequestBody Map<String,String> body) {
        String password=text(body,"newPassword");String confirmation=text(body,"confirmPassword");
        if(password.length()<6)return ResponseEntity.badRequest().body(Map.of("error","Mật khẩu mới phải có ít nhất 6 ký tự."));
        if(!password.equals(confirmation))return ResponseEntity.badRequest().body(Map.of("error","Mật khẩu xác nhận không khớp."));
        return userRepository.findById(id).map(u->{u.setPassword(passwordEncoder.encode(password));return ResponseEntity.ok(userSafe(userRepository.save(u)));}).orElse(ResponseEntity.notFound().build());
    }
    @PutMapping("/admin/users/{id}/phone") @PreAuthorize("hasAnyRole('ADMIN','STAFF','CUSTOMER')") public ResponseEntity<?> updatePhone(@PathVariable Long id,@RequestBody Map<String,String> body,Principal principal,Authentication authentication) {
        AppUser u=userRepository.findById(id).orElse(null);if(u==null)return ResponseEntity.notFound().build();
        boolean privileged=authentication.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_STAFF"));
        if(!privileged&&!u.getUsername().equals(principal.getName()))return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String phone=text(body,"phone").trim();if(phone.length()>20)return ResponseEntity.badRequest().body(Map.of("error","Số điện thoại tối đa 20 ký tự."));
        u.setPhone(phone.isBlank()?null:phone);return ResponseEntity.ok(userSafe(userRepository.save(u)));
    }
    @PutMapping("/admin/users/{id}/toggle") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> toggleUser(@PathVariable Long id,@RequestBody(required=false)Map<String,Object> body,Principal principal) {
        AppUser u=userRepository.findById(id).orElse(null);if(u==null)return ResponseEntity.notFound().build();
        if(u.getUsername().equals(principal.getName()))return ResponseEntity.badRequest().body(Map.of("error","Không thể khóa chính tài khoản đang đăng nhập."));
        boolean enabled=body!=null&&Boolean.TRUE.equals(body.get("enabled"));
        if(!enabled&&u.isEnabled()&&"ADMIN".equals(u.getRole())&&userRepository.countByRoleAndEnabledTrue("ADMIN")<=1)
            return ResponseEntity.badRequest().body(Map.of("error","Không thể khóa quản trị viên đang hoạt động cuối cùng."));
        u.setEnabled(enabled);return ResponseEntity.ok(userSafe(userRepository.save(u)));
    }
    @PutMapping("/admin/users/{id}") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> renameUser(@PathVariable Long id,@RequestBody Map<String,String> body) { AppUser u=userRepository.findById(id).orElse(null);if(u==null)return ResponseEntity.notFound().build();String username=text(body,"username"),fullName=text(body,"fullName");if(username.isBlank()||username.length()>50||fullName.length()>100)return ResponseEntity.badRequest().body(Map.of("error","Thông tin tài khoản không hợp lệ."));if(userRepository.findByUsername(username).filter(x->!x.getId().equals(id)).isPresent())return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error","Tên đăng nhập đã tồn tại."));String old=u.getUsername();if(!old.equals(username)){parkingRecordRepository.renameCheckInAccount(old,username);parkingRecordRepository.renameCheckOutAccount(old,username);u.setUsername(username);}u.setFullName(fullName);return ResponseEntity.ok(userSafe(userRepository.save(u))); }
    @DeleteMapping("/admin/users/{id}") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> deleteUser(@PathVariable Long id,Principal principal) {
        AppUser user=userRepository.findById(id).orElse(null);if(user==null)return ResponseEntity.notFound().build();
        if(user.getUsername().equals(principal.getName()))return ResponseEntity.badRequest().body(Map.of("error","Không thể xóa tài khoản đang đăng nhập."));
        if("ADMIN".equals(user.getRole())&&user.isEnabled()&&userRepository.countByRoleAndEnabledTrue("ADMIN")<=1)
            return ResponseEntity.badRequest().body(Map.of("error","Không thể xóa quản trị viên đang hoạt động cuối cùng."));
        userRepository.delete(user);return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/pricing") @PreAuthorize("hasRole('ADMIN')") public List<Map<String,Object>> pricingList() { return pricingRepository.findAll().stream().map(this::pricing).toList(); }
    @PutMapping("/admin/pricing/{id}") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> updatePricing(@PathVariable Long id,@RequestBody Map<String,Object> body) { Pricing p=pricingRepository.findById(id).orElse(null);if(p==null)return ResponseEntity.notFound().build(); long turn=Long.parseLong(text(body,"turnPrice","0"));long month=Long.parseLong(text(body,"monthPrice","0"));int capacity=Integer.parseInt(text(body,"capacity","0"));long hourly=Long.parseLong(text(body,"reservationHourlyPrice","0"));if(turn<0||month<0||capacity<0||hourly<=0)return ResponseEntity.badRequest().body(Map.of("error","Giá và sức chứa không hợp lệ."));p.setTurnPrice(turn);p.setMonthPrice(month);p.setCapacity(capacity);p.setReservationHourlyPrice(hourly);return ResponseEntity.ok(pricing(pricingRepository.save(p))); }

    @GetMapping("/admin/reports") @PreAuthorize("hasRole('ADMIN')") public ReportService.Summary report(@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){LocalDate f=from==null?LocalDate.now().withDayOfMonth(1):from;LocalDate t=to==null?LocalDate.now():to;return reportService.build(f,t);}
    @GetMapping(value="/admin/reports/export",produces="text/csv") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<byte[]> reportExport(@RequestParam@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate from,@RequestParam@DateTimeFormat(iso=DateTimeFormat.ISO.DATE)LocalDate to){byte[] body=("\uFEFF"+reportService.toCsv(reportService.build(from,to))).getBytes(StandardCharsets.UTF_8);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=baocao_"+from+"_"+to+".csv").contentType(MediaType.parseMediaType("text/csv; charset=UTF-8")).body(body);}

    @GetMapping("/admin/reservations") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public List<Map<String,Object>> adminReservations() { return reservationService.allBookings().stream().map(this::reservation).toList(); }
    @PostMapping("/admin/reservations/{id}/confirm-payment") @PreAuthorize("hasAnyRole('ADMIN','STAFF')") public ResponseEntity<?> confirmReservation(@PathVariable Long id,@RequestBody(required=false)Map<String,Object> body,Principal principal) { if(body==null||!Boolean.TRUE.equals(body.get("paymentVerified")))return ResponseEntity.badRequest().body(Map.of("error","Hãy xác nhận đã kiểm tra giao dịch thành công.")); try{ParkingReservation r=reservationService.confirmPayment(id);notificationService.notifyCustomer(r.getCustomerUsername(),r.getId(),"Đặt chỗ "+r.getBookingCode()+" đã được xác nhận thanh toán thành công.");return ResponseEntity.ok(reservation(r));}catch(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));} }

    @GetMapping("/admin/support/conversations") @PreAuthorize("hasRole('ADMIN')") public List<SupportChatService.ConversationResponse> conversations(){return supportChatService.conversations();}
    @GetMapping("/admin/support/conversations/{username}/messages") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> adminSupportMessages(@PathVariable String username){try{return ResponseEntity.ok(supportChatService.adminMessages(username));}catch(IllegalArgumentException ex){return ResponseEntity.notFound().build();}}
    @PostMapping("/admin/support/conversations/{username}/messages") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> adminSupportReply(@PathVariable String username,@RequestBody Map<String,String> body,Principal principal){String message=text(body,"message");if(message.isBlank()||message.length()>2000)return ResponseEntity.badRequest().body(Map.of("error","Tin nhắn không hợp lệ."));try{return ResponseEntity.ok(supportChatService.replyFromAdmin(principal.getName(),username,message));}catch(IllegalArgumentException ex){return ResponseEntity.notFound().build();}}
    @PostMapping("/admin/support/conversations/{username}/block") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> blockSupport(@PathVariable String username,@RequestBody Map<String,Object> body){Object value=body.get("blocked");if(value==null)return ResponseEntity.badRequest().body(Map.of("error","Thiếu trạng thái chặn."));try{return ResponseEntity.ok(Map.of("blocked",supportChatService.setCustomerChatBlocked(username,Boolean.parseBoolean(value.toString()))));}catch(IllegalArgumentException ex){return ResponseEntity.notFound().build();}}
    @GetMapping("/admin/support/unread") @PreAuthorize("hasRole('ADMIN')") public Map<String,Long> adminSupportUnread(){return Map.of("count",supportChatService.unreadCustomerMessageCount());}
    @GetMapping("/customer/support/status") @PreAuthorize("hasRole('CUSTOMER')") public Map<String,Object> customerSupportStatus(Principal principal){return Map.of("adminOnline",supportChatService.isAdminOnline(),"chatBlocked",supportChatService.isCustomerChatBlocked(principal.getName()),"unreadCount",supportChatService.unreadAdminMessageCount(principal.getName()));}
    @GetMapping("/support/messages/{messageId}/image") @PreAuthorize("hasAnyRole('ADMIN','CUSTOMER')") public ResponseEntity<?> supportImage(@PathVariable Long messageId,Principal principal,Authentication authentication){boolean admin=authentication.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN"));try{SupportChatService.MessageImage image=supportChatService.imageForViewer(principal.getName(),admin,messageId);return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType())).header(HttpHeaders.CACHE_CONTROL,"no-store").body(image.data());}catch(IllegalArgumentException ex){return ResponseEntity.notFound().build();}}
    @DeleteMapping("/admin/support/conversations/{username}/messages/{messageId}") @PreAuthorize("hasRole('ADMIN')") public ResponseEntity<?> deleteSupportMessage(@PathVariable String username,@PathVariable Long messageId){try{supportChatService.deleteMessage(username,messageId);return ResponseEntity.noContent().build();}catch(IllegalArgumentException ex){return ResponseEntity.notFound().build();}}
    @GetMapping("/admin/support/stream") @PreAuthorize("hasRole('ADMIN')") public SseEmitter adminSupportStream(){return supportChatService.subscribeAdmin();}

    private String text(Map<String,?> body,String key){Object v=body==null?null:body.get(key);return v==null?"":v.toString().trim();}
    private String text(Map<String,?> body,String key,String defaultValue){String v=text(body,key);return v.isEmpty()?defaultValue:v;}
    private String blankToNull(String value){return value==null||value.isBlank()?null:value.trim();}
    private byte[] vehiclePhoto(Map<String,String> body,String key){String dataUrl=text(body,key);if(dataUrl.isEmpty())return null;String prefix="data:image/jpeg;base64,";if(!dataUrl.startsWith(prefix))throw new IllegalArgumentException("Ảnh check-in phải ở định dạng JPEG.");String encoded=dataUrl.substring(prefix.length());if(encoded.isEmpty()||encoded.length()>2_796_204)throw new IllegalArgumentException("Ảnh check-in vượt quá dung lượng 2 MB.");byte[] image;try{image=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Dữ liệu ảnh check-in không hợp lệ.",ex);}if(image.length==0||image.length>2*1024*1024)throw new IllegalArgumentException("Ảnh check-in vượt quá dung lượng 2 MB.");return image;}
    private Map<String,Object> pricing(Pricing p){Map<String,Object> m=new LinkedHashMap<>();m.put("id",p.getId());m.put("vehicleType",p.getVehicleType().name());m.put("vehicleTypeLabel",p.getVehicleType().getLabel());m.put("turnPrice",p.getTurnPrice());m.put("monthPrice",p.getMonthPrice());m.put("capacity",p.getCapacity());m.put("reservationHourlyPrice",p.getReservationHourlyPrice());return m;}
    private Map<String,Object> userSafe(AppUser u){Map<String,Object>m=new LinkedHashMap<>();m.put("id",u.getId());m.put("username",u.getUsername());m.put("fullName",u.getFullName());m.put("role",u.getRole());m.put("phone",u.getPhone());m.put("enabled",u.isEnabled());return m;}
    private Map<String,Object> reservation(ParkingReservation r){Map<String,Object>m=new LinkedHashMap<>();m.put("id",r.getId());m.put("bookingCode",r.getBookingCode());m.put("customerUsername",r.getCustomerUsername());m.put("customerName",r.getCustomerName());m.put("plate",r.getLicensePlate());m.put("vehicleType",r.getVehicleType().name());m.put("slotCode",r.getSlotCode());m.put("startTime",r.getStartTime());m.put("endTime",r.getEndTime());m.put("hourlyRate",r.getHourlyRate());m.put("totalAmount",r.getTotalAmount());m.put("status",r.getStatus().name());m.put("statusLabel",r.getStatus().getLabel());m.put("createdAt",r.getCreatedAt());return m;}
    private Map<String,Object> record(ParkingRecord r){Map<String,Object>m=new LinkedHashMap<>();m.put("id",r.getId());m.put("plate",r.getLicensePlate());m.put("slotCode",r.getSlotCode());m.put("vehicleType",r.getVehicleType().name());m.put("checkIn",r.getCheckIn());m.put("checkOut",r.getCheckOut());m.put("fee",r.getFee());m.put("monthlyTicket",r.isMonthlyTicket());m.put("paymentMethod",r.getPaymentMethod()==null?null:r.getPaymentMethod().name());m.put("createdBy",r.getCreatedBy());m.put("checkedOutBy",r.getCheckedOutBy());return m;}
    private Map<String,Object> ticket(MonthlyTicket t){Map<String,Object>m=new LinkedHashMap<>();m.put("id",t.getId());m.put("licensePlate",t.getLicensePlate());m.put("ownerName",t.getOwnerName());m.put("phone",t.getPhone());m.put("vehicleType",t.getVehicleType().name());m.put("startDate",t.getStartDate());m.put("endDate",t.getEndDate());m.put("price",t.getPrice());m.put("renewalCancelled",t.isRenewalCancelled());m.put("cancelledAt",t.getCancelledAt());m.put("refundAmount",t.getRefundAmount());return m;}
    private Map<String,Object> parkingSection(ParkingService.ParkingSection s){Map<String,Object>m=new LinkedHashMap<>();m.put("type",s.type().name());m.put("typeLabel",s.type().getLabel());m.put("stats",s.stats());m.put("slots",s.slots());m.put("overflowRecords",s.overflowRecords().stream().map(this::record).toList());return m;}
}
