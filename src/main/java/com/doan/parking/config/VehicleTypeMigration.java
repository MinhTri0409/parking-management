package com.doan.parking.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class VehicleTypeMigration implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;

    public VehicleTypeMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int legacyPricingRows = jdbcTemplate.queryForObject(
                "select count(*) from pricing where vehicle_type = 'BICYCLE'", Integer.class);
        int targetPricingRows = jdbcTemplate.queryForObject(
                "select count(*) from pricing where vehicle_type = 'MOTORELECTRIC'", Integer.class);
        if (legacyPricingRows > 0 && targetPricingRows > 0) {
            throw new IllegalStateException(
                    "Cannot rename BICYCLE pricing: both BICYCLE and MOTORELECTRIC pricing rows exist.");
        }

        List<LegacySlot> legacySlots = jdbcTemplate.query(
                "select id, slot_code from parking_record "
                        + "where slot_code like 'BICYCLE-%' or slot_code like 'ELECTRICMOTORCYCLE-%'",
                (resultSet, rowNum) -> new LegacySlot(
                        resultSet.getLong("id"), resultSet.getString("slot_code")));
        for (LegacySlot legacySlot : legacySlots) {
            int separator = legacySlot.slotCode().lastIndexOf('-');
            jdbcTemplate.update("update parking_record set slot_code = ? where id = ?",
                    "MOTORELECTRIC" + legacySlot.slotCode().substring(separator),
                    legacySlot.id());
        }
        jdbcTemplate.update(
                "update parking_record set vehicle_type = 'MOTORELECTRIC' where vehicle_type = 'BICYCLE'");
        jdbcTemplate.update(
                "update monthly_ticket set vehicle_type = 'MOTORELECTRIC' where vehicle_type = 'BICYCLE'");
        jdbcTemplate.update(
                "update pricing set vehicle_type = 'MOTORELECTRIC' where vehicle_type = 'BICYCLE'");
    }

    private record LegacySlot(long id, String slotCode) {
    }
}
