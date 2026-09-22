package com.chat99.server.feedback;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, Long>,
    JpaSpecificationExecutor<UserFeedback> {
}
