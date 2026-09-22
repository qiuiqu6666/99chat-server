package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.WorkerStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LifePaymentWorkerDeviceRepository extends JpaRepository<LifePaymentWorkerDevice, Long> {
    Optional<LifePaymentWorkerDevice> findByWorkerId(String workerId);

    Optional<LifePaymentWorkerDevice> findByWorkerTokenHash(String workerTokenHash);

    List<LifePaymentWorkerDevice> findAllByOrderByUpdatedAtDesc();

    List<LifePaymentWorkerDevice> findByStatusOrderByUpdatedAtDesc(WorkerStatus status);
}
