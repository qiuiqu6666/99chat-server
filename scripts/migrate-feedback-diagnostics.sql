CREATE TABLE IF NOT EXISTS feedback_diagnostics (
    feedback_id BIGINT NOT NULL PRIMARY KEY,
    report MEDIUMBLOB NOT NULL,
    CONSTRAINT fk_feedback_diagnostics_feedback FOREIGN KEY (feedback_id)
        REFERENCES user_feedback(id) ON DELETE CASCADE
) ENGINE=InnoDB;
