-- Cấu trúc tham khảo cho MySQL parking_management.
-- Ứng dụng tự tạo/cập nhật cấu trúc theo JPA khi DB_DDL_AUTO=update.
CREATE DATABASE IF NOT EXISTS parking_management
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE parking_management;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password VARCHAR(100) NOT NULL,
  full_name VARCHAR(100),
  role VARCHAR(20) NOT NULL,
  phone VARCHAR(20),
  enabled BIT NOT NULL DEFAULT 1,
  support_chat_blocked BIT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS pricing (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  vehicle_type VARCHAR(20) NOT NULL UNIQUE,
  turn_price BIGINT NOT NULL,
  month_price BIGINT NOT NULL,
  capacity INT NOT NULL,
  reservation_hourly_price BIGINT
);

CREATE TABLE IF NOT EXISTS monthly_ticket (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  license_plate VARCHAR(20) NOT NULL,
  owner_name VARCHAR(100) NOT NULL,
  phone VARCHAR(20),
  vehicle_type VARCHAR(20) NOT NULL,
  start_date DATE,
  end_date DATE,
  price BIGINT NOT NULL,
  renewal_cancelled BIT NOT NULL DEFAULT 0,
  cancelled_at DATETIME(6),
  refund_amount BIGINT NOT NULL DEFAULT 0,
  INDEX idx_ticket_plate (license_plate)
);

CREATE TABLE IF NOT EXISTS parking_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  license_plate VARCHAR(20) NOT NULL,
  slot_code VARCHAR(30),
  vehicle_type VARCHAR(20) NOT NULL,
  check_in DATETIME(6) NOT NULL,
  check_out DATETIME(6),
  fee BIGINT NOT NULL DEFAULT 0,
  monthly_ticket BIT NOT NULL DEFAULT 0,
  created_by VARCHAR(50),
  checked_out_by VARCHAR(50),
  payment_method VARCHAR(20),
  INDEX idx_record_plate (license_plate)
);

CREATE TABLE IF NOT EXISTS parking_record_photos (
  parking_record_id BIGINT PRIMARY KEY,
  front_photo LONGBLOB,
  plate_photo LONGBLOB,
  CONSTRAINT fk_parking_record_photos_record
    FOREIGN KEY (parking_record_id) REFERENCES parking_record(id)
    ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS parking_reservation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  booking_code VARCHAR(20) NOT NULL UNIQUE,
  customer_username VARCHAR(50) NOT NULL,
  customer_name VARCHAR(100) NOT NULL,
  license_plate VARCHAR(20) NOT NULL,
  vehicle_type VARCHAR(20) NOT NULL,
  slot_code VARCHAR(30) NOT NULL,
  start_time DATETIME(6) NOT NULL,
  end_time DATETIME(6) NOT NULL,
  hourly_rate BIGINT NOT NULL,
  total_amount BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  payment_confirmed_at DATETIME(6),
  INDEX idx_reservation_customer_created (customer_username, created_at),
  INDEX idx_reservation_slot_time (vehicle_type, slot_code, start_time, end_time, status)
);

CREATE TABLE IF NOT EXISTS customer_notification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient_username VARCHAR(50) NOT NULL,
  reservation_id BIGINT UNIQUE,
  message VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  read_at DATETIME(6),
  INDEX idx_customer_notification_pending (recipient_username, read_at, created_at)
);

CREATE TABLE IF NOT EXISTS support_chat_message (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_username VARCHAR(50) NOT NULL,
  sender_username VARCHAR(50) NOT NULL,
  sender_role VARCHAR(20) NOT NULL,
  message VARCHAR(2000) NOT NULL,
  image_data LONGBLOB,
  image_content_type VARCHAR(20),
  created_at DATETIME(6) NOT NULL,
  read_at DATETIME(6),
  INDEX idx_support_chat_customer_time (customer_username, created_at),
  INDEX idx_support_chat_unread (customer_username, sender_role, read_at)
);

CREATE TABLE IF NOT EXISTS contact_inquiry (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  phone VARCHAR(20) NOT NULL,
  email VARCHAR(254) NOT NULL,
  customer_type VARCHAR(20) NOT NULL,
  message VARCHAR(5000) NOT NULL,
  email_status VARCHAR(20) NOT NULL,
  submitted_at DATETIME(6) NOT NULL
);
