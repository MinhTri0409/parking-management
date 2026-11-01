package com.doan.parking.repository;

import com.doan.parking.entity.ContactInquiry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactInquiryRepository extends JpaRepository<ContactInquiry, Long> {
}
