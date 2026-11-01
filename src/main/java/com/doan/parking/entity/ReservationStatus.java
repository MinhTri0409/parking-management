package com.doan.parking.entity;

/** Trạng thái xử lý đơn đặt chỗ. */
public enum ReservationStatus {
    PENDING_PAYMENT("Chờ xác nhận thanh toán"),
    CONFIRMED("Đã xác nhận"),
    ARRIVED("Xe đã vào bãi"),
    COMPLETED("Đã hoàn tất"),
    CANCELLED("Đã hủy");

    private final String label;

    ReservationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
