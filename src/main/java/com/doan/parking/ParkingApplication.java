package com.doan.parking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Lớp khởi chạy ứng dụng Spring Boot (điểm bắt đầu của chương trình). */
@SpringBootApplication
public class ParkingApplication {
    // Hàm main: khởi động server web (mặc định cổng 8080)
    public static void main(String[] args) {
        SpringApplication.run(ParkingApplication.class, args);
    }
}
