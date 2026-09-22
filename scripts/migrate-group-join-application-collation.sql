-- Align join-application tables with group_member (utf8mb4_0900_ai_ci).
-- Fixes: Illegal mix of collations on /me/join-applications EXISTS group_member subquery.
ALTER TABLE group_join_application
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE group_join_application_dismiss
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
