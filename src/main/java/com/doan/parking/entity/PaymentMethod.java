package com.doan.parking.entity;

/** Phương thức thanh toán tại thời điểm xe rời bãi. */
public enum PaymentMethod {
    CASH("Tiền mặt"),
    QR("QR ngân hàng"),
    CARD("Thẻ tín dụng"),
    ALREADY_PAID("Đã thanh toán rồi"),
    MONTHLY_TICKET("Vé tháng");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
