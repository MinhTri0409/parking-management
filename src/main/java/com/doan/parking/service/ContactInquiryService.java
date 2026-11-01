package com.doan.parking.service;

import com.doan.parking.entity.ContactInquiry;
import com.doan.parking.repository.ContactInquiryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class ContactInquiryService {
    private final ContactInquiryRepository contactInquiryRepository;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.host:}")
    private String mailHost;
    @Value("${spring.mail.username:}")
    private String mailUsername;
    @Value("${app.contact.email:trantridung10122006@gmail.com}")
    private String contactEmail;
    @Value("${app.contact.from:}")
    private String contactFrom;

    public boolean saveAndSend(String name, String phone, String email, String customerType, String message) {
        ContactInquiry inquiry = new ContactInquiry();
        inquiry.setName(name);
        inquiry.setPhone(phone);
        inquiry.setEmail(email);
        inquiry.setCustomerType(customerType);
        inquiry.setMessage(message);
        inquiry.setEmailStatus("PENDING");
        inquiry.setSubmittedAt(LocalDateTime.now());
        contactInquiryRepository.saveAndFlush(inquiry);

        if (mailHost.isBlank() || (mailUsername.isBlank() && contactFrom.isBlank())) {
            inquiry.setEmailStatus("FAILED");
            contactInquiryRepository.save(inquiry);
            log.warn("Contact inquiry {} was saved, but outbound email is not configured", inquiry.getId());
            return false;
        }

        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            inquiry.setEmailStatus("FAILED");
            contactInquiryRepository.save(inquiry);
            log.warn("Contact inquiry {} was saved, but the mail service is unavailable", inquiry.getId());
            return false;
        }

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(contactFrom.isBlank() ? mailUsername : contactFrom);
        mail.setTo(contactEmail);
        mail.setReplyTo(email);
        mail.setSubject("Liên hệ từ website Nhà xe Hiếu Minh");
        mail.setText("Tên khách hàng: %s\nSố điện thoại: %s\nEmail: %s\nLoại khách hàng: %s\n\nNội dung:\n%s"
                .formatted(name, phone, email, customerType, message));
        try {
            sender.send(mail);
        } catch (MailException exception) {
            inquiry.setEmailStatus("FAILED");
            contactInquiryRepository.save(inquiry);
            log.error("Contact inquiry {} was saved, but email delivery failed", inquiry.getId(), exception);
            return false;
        }

        inquiry.setEmailStatus("SENT");
        contactInquiryRepository.save(inquiry);
        return true;
    }
}
