package com.doan.parking.entity;

/** Các loại xe được gửi trong bãi, kèm tên hiển thị tiếng Việt. */
public enum VehicleType {
    MOTORBIKE("Xe máy"), MOTORELECTRIC("Xe máy điện"), CAR("Ô tô");

    // Tên hiển thị trên giao diện
    private final String label;
    VehicleType(String label) { this.label = label; }
    public String getLabel() { return label; }
}
