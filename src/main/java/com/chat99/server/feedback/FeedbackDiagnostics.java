package com.chat99.server.feedback;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Private attachment, deliberately separate from feedback list queries. */
@Entity
@Table(name = "feedback_diagnostics")
@Getter @Setter @NoArgsConstructor
public class FeedbackDiagnostics {
    @Id
    @Column(name = "feedback_id")
    private Long feedbackId;
    @Lob
    @Column(name = "report", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] report;
}
