package com.chat99.server.adminapi;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminDashboardDailyRepository extends JpaRepository<AdminDashboardDaily, LocalDate> {

    List<AdminDashboardDaily> findByStatDateGreaterThanEqualOrderByStatDateDesc(LocalDate from);
}
