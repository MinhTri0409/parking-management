package com.doan.parking.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ParkingRecordPhotosMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public ParkingRecordPhotosMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean mysql = Boolean.TRUE.equals(jdbcTemplate.execute(
                (ConnectionCallback<Boolean>) connection -> connection.getMetaData()
                        .getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")));
        if (mysql) {
            jdbcTemplate.execute(
                    "ALTER TABLE parking_record_photos "
                            + "MODIFY COLUMN front_photo LONGBLOB NULL, "
                            + "MODIFY COLUMN plate_photo LONGBLOB NULL");
        }
    }
}
