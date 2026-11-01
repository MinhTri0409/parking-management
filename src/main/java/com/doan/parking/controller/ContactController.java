package com.doan.parking.controller;

import com.doan.parking.service.ContactInquiryService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.regex.Pattern;

@Controller
@RequiredArgsConstructor
public class ContactController {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[0-9+().\\- ]{7,20}$");

    private final ContactInquiryService contactInquiryService;

    @GetMapping("/contact")
    public String contact(Model model, @RequestParam(required = false) String sent,
                          @RequestParam(required = false) String emailSent) {
        model.addAttribute("contactForm", new ContactForm());
        model.addAttribute("sent", sent != null);
        model.addAttribute("emailSent", "true".equals(emailSent));
        return "contact";
    }

    @PostMapping("/contact")
    public String sendContact(@ModelAttribute ContactForm contactForm,
                              Model model,
                              RedirectAttributes redirectAttributes) {
        String validationError = validate(contactForm);
        if (validationError != null) {
            model.addAttribute("error", validationError);
            model.addAttribute("contactForm", contactForm);
            return "contact";
        }

        boolean emailSent = contactInquiryService.saveAndSend(contactForm.getName().trim(),
                contactForm.getPhone().trim(), contactForm.getEmail().trim(), contactForm.getCustomerType(),
                contactForm.getMessage().trim());
        redirectAttributes.addAttribute("sent", true);
        redirectAttributes.addAttribute("emailSent", emailSent);
        return "redirect:/contact";
    }

    private String validate(ContactForm form) {
        if (form.getName() == null || form.getName().isBlank() || form.getName().trim().length() > 100
                || containsLineBreak(form.getName())) {
            return "Vui lòng nhập tên hợp lệ (tối đa 100 ký tự).";
        }
        if (form.getPhone() == null || !PHONE_PATTERN.matcher(form.getPhone().trim()).matches()) {
            return "Vui lòng nhập số điện thoại hợp lệ.";
        }
        if (form.getEmail() == null || form.getEmail().trim().length() > 254
                || !EMAIL_PATTERN.matcher(form.getEmail().trim()).matches()) {
            return "Vui lòng nhập địa chỉ email hợp lệ.";
        }
        if (!"NEW".equals(form.getCustomerType()) && !"EXISTING".equals(form.getCustomerType())) {
            return "Vui lòng chọn loại khách hàng.";
        }
        if (form.getMessage() == null || form.getMessage().trim().length() < 10
                || form.getMessage().trim().length() > 5000) {
            return "Nội dung cần có từ 10 đến 5.000 ký tự.";
        }
        return null;
    }

    private boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    @Getter
    @Setter
    public static class ContactForm {
        private String name;
        private String phone;
        private String email;
        private String customerType;
        private String message;
    }
}
