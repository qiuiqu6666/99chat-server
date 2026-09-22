package com.chat99.server.adminapi;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminBannedDeviceRepository extends JpaRepository<AdminBannedDevice, String> {
}
