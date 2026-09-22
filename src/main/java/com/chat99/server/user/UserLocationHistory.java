package com.chat99.server.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "user_location_history", indexes = {
    @Index(name = "idx_ulh_user_collected", columnList = "user_id,collected_at"),
    @Index(name = "idx_ulh_collected", columnList = "collected_at")
})
@Getter
@Setter
@NoArgsConstructor
public class UserLocationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 10)
    private String userId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "latitude", nullable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false)
    private double longitude;

    @Column(name = "accuracy")
    private Double accuracy;

    @Column(name = "altitude")
    private Double altitude;

    @Column(name = "heading")
    private Double heading;

    @Column(name = "speed")
    private Double speed;

    @Column(name = "source", length = 16)
    private String source;

    @Column(name = "geohash", length = 16)
    private String geohash;

    @Column(name = "country", length = 64)
    private String country;

    @Column(name = "province", length = 64)
    private String province;

    @Column(name = "city", length = 64)
    private String city;

    @Column(name = "district", length = 64)
    private String district;

    @Column(name = "city_label", length = 128)
    private String cityLabel;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "server_received_at", nullable = false)
    private Instant serverReceivedAt;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (serverReceivedAt == null) {
            serverReceivedAt = createdAt;
        }
    }
}
