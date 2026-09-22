package com.chat99.server.adminapi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardCounterService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AdminDashboardDailyRepository dailyRepository;

    public AdminDashboardCounterService(AdminDashboardDailyRepository dailyRepository) {
        this.dailyRepository = dailyRepository;
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }

    public static Instant startOfNextDay(LocalDate date) {
        return date.plusDays(1).atStartOfDay(ZONE).toInstant();
    }

    @Transactional
    public void incrementC2cMessage() {
        bumpMessages(1, 0);
    }

    @Transactional
    public void incrementGroupMessage() {
        bumpMessages(0, 1);
    }

    @Transactional
    public void incrementGroupsCreated() {
        AdminDashboardDaily row = getOrCreate(today());
        row.setGroupsCreatedCount(row.getGroupsCreatedCount() + 1);
        dailyRepository.save(row);
    }

    @Transactional
    public void incrementSystemError() {
        AdminDashboardDaily row = getOrCreate(today());
        row.setSystemErrorCount(row.getSystemErrorCount() + 1);
        dailyRepository.save(row);
    }

    @Transactional
    AdminDashboardDaily getOrCreate(LocalDate date) {
        return dailyRepository.findById(date).orElseGet(() -> {
            AdminDashboardDaily created = new AdminDashboardDaily();
            created.setStatDate(date);
            return dailyRepository.save(created);
        });
    }

    private void bumpMessages(long c2cDelta, long groupDelta) {
        AdminDashboardDaily row = getOrCreate(today());
        if (c2cDelta > 0) {
            row.setC2cMessageCount(row.getC2cMessageCount() + c2cDelta);
        }
        if (groupDelta > 0) {
            row.setGroupMessageCount(row.getGroupMessageCount() + groupDelta);
        }
        dailyRepository.save(row);
    }
}
