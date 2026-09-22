/*
 * Decompiled with CFR 0.152.
 */
package com.chat99.server.complaint;

import com.chat99.server.complaint.ChatComplaint;
import com.chat99.server.complaint.ChatComplaintType;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ChatComplaintRepository
extends JpaRepository<ChatComplaint, Long>,
JpaSpecificationExecutor<ChatComplaint> {
    public boolean existsByReporterUserIdAndChatTypeAndReportedUserIdAndGroupIdAndMsgKeyAndCreatedAtAfter(String var1, ChatComplaintType var2, String var3, String var4, String var5, Instant var6);
}
