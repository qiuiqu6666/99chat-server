package com.chat99.server.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceModelDisplayServiceTest {

    private DeviceModelDisplayService service;

    @BeforeEach
    void setUp() {
        service = new DeviceModelDisplayService(new ObjectMapper());
    }

    @Test
    void mapsIphoneHardwareId() {
        assertThat(service.display("ios", "iPhone17,1")).isEqualTo("iPhone 16 Pro");
        assertThat(service.display("ios", "iPhone18,2")).isEqualTo("iPhone 17 Pro Max");
    }

    @Test
    void mapsGenericIosLabel() {
        assertThat(service.display("ios", "iOS")).isEqualTo("iPhone");
        assertThat(service.display("IOS", null)).isEqualTo("iPhone");
    }

    @Test
    void keepsUnknownAndroidModel() {
        assertThat(service.display("android", "nubia NX789J")).isEqualTo("nubia NX789J");
    }

    @Test
    void unknownAppleIdFallsBackToFamily() {
        assertThat(service.display("ios", "iPhone99,9")).isEqualTo("iPhone");
    }
}
