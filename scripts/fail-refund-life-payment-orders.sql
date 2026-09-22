-- 将已扣款但未完结的生活缴费订单标记失败并原路退款（幂等：platform_pay_status 已为 refunded 则跳过）
-- 用法: mysql ... < scripts/fail-refund-life-payment-orders.sql

SET @reason = '未处理订单批量标记失败并退款';

DROP PROCEDURE IF EXISTS lp_fail_refund_order;
DELIMITER $$
CREATE PROCEDURE lp_fail_refund_order(IN p_order_id BIGINT, IN p_reason VARCHAR(255))
proc: BEGIN
    DECLARE v_user_id VARCHAR(10);
    DECLARE v_order_no VARCHAR(64);
    DECLARE v_service_type VARCHAR(20);
    DECLARE v_platform_pay_status VARCHAR(20);
    DECLARE v_order_status VARCHAR(32);
    DECLARE v_debit BIGINT DEFAULT 0;
    DECLARE v_credit BIGINT DEFAULT 0;
    DECLARE v_remain BIGINT DEFAULT 0;
    DECLARE v_currency VARCHAR(16);
    DECLARE v_balance_after BIGINT;

    SELECT user_id, order_no, service_type, platform_pay_status, order_status
      INTO v_user_id, v_order_no, v_service_type, v_platform_pay_status, v_order_status
      FROM life_payment_orders
     WHERE id = p_order_id
     FOR UPDATE;

    IF v_order_no IS NULL THEN
        LEAVE proc;
    END IF;
    IF v_order_status IN ('success', 'cancelled') THEN
        LEAVE proc;
    END IF;
    IF v_platform_pay_status = 'refunded' THEN
        LEAVE proc;
    END IF;

    SELECT
        COALESCE(SUM(CASE WHEN amount < 0 THEN -amount ELSE 0 END), 0),
        COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0),
        MAX(CASE WHEN amount < 0 THEN currency END)
      INTO v_debit, v_credit, v_currency
      FROM wallet_ledger
     WHERE ref_type = 'LIFE_PAYMENT'
       AND ref_id = p_order_id;

    SET v_remain = v_debit - v_credit;

    IF v_platform_pay_status = 'paid' AND v_remain > 0 AND v_currency IS NOT NULL THEN
        IF v_currency = 'PLATFORM' THEN
            UPDATE user_wallet
               SET balance_platform_fen = balance_platform_fen + v_remain,
                   version = version + 1,
                   updated_at = NOW(6)
             WHERE user_id = v_user_id;
            SELECT balance_platform_fen INTO v_balance_after FROM user_wallet WHERE user_id = v_user_id;
        ELSEIF v_currency = 'USDT' THEN
            UPDATE user_wallet
               SET balance_usdt_micro = balance_usdt_micro + v_remain,
                   version = version + 1,
                   updated_at = NOW(6)
             WHERE user_id = v_user_id;
            SELECT balance_usdt_micro INTO v_balance_after FROM user_wallet WHERE user_id = v_user_id;
        ELSE
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'unsupported refund currency';
        END IF;

        INSERT INTO wallet_ledger (
            user_id, currency, amount, balance_after, ledger_type,
            ref_type, ref_id, remark, created_at
        ) VALUES (
            v_user_id, v_currency, v_remain, v_balance_after, 'LIFE_PAYMENT',
            'LIFE_PAYMENT_REFUND', p_order_id,
            CONCAT('生活缴费失败退款 ', v_order_no, ': ', p_reason),
            NOW(6)
        );
    END IF;

    UPDATE life_payment_orders
       SET order_status = 'failed',
           plugin_status = 'failed',
           platform_pay_status = 'refunded',
           updated_at = NOW(6)
     WHERE id = p_order_id;

    UPDATE life_payment_task_queue
       SET status = 'failed',
           finished_at = NOW(6),
           locked_by = NULL,
           locked_at = NULL,
           last_error_code = 'admin_fail_refund',
           last_error_message = p_reason,
           updated_at = NOW(6)
     WHERE order_no = v_order_no
       AND status IN ('ready', 'running', 'failed', 'need_manual');

    IF v_service_type = 'mobile' THEN
        UPDATE life_payment_mobile_details
           SET recharge_status = '充值失败'
         WHERE order_no = v_order_no;
    ELSEIF v_service_type IN ('water', 'electric', 'gas') THEN
        UPDATE life_payment_utility_details
           SET utility_status = '缴费失败'
         WHERE order_no = v_order_no;
    END IF;

    INSERT INTO life_payment_operation_logs (
        order_no, service_type, actor_type, actor_id, action, message, created_at
    ) VALUES (
        v_order_no, v_service_type, 'admin', 'ops-script', 'mark_failed_refund', p_reason, NOW(6)
    );
END$$
DELIMITER ;

START TRANSACTION;

SELECT id FROM life_payment_orders
 WHERE platform_pay_status = 'paid'
   AND order_status NOT IN ('success', 'cancelled')
 ORDER BY id;

-- 逐单处理（当前环境待处理订单 id 列表）
CALL lp_fail_refund_order(1, @reason);
CALL lp_fail_refund_order(3, @reason);
CALL lp_fail_refund_order(4, @reason);
CALL lp_fail_refund_order(9, @reason);
CALL lp_fail_refund_order(11, @reason);
CALL lp_fail_refund_order(14, @reason);
CALL lp_fail_refund_order(17, @reason);
CALL lp_fail_refund_order(23, @reason);
CALL lp_fail_refund_order(27, @reason);
CALL lp_fail_refund_order(28, @reason);

COMMIT;

DROP PROCEDURE IF EXISTS lp_fail_refund_order;

SELECT '--- after refund ---' AS section;
SELECT order_status, platform_pay_status, COUNT(*) cnt
FROM life_payment_orders
GROUP BY order_status, platform_pay_status
ORDER BY cnt DESC;

SELECT id, order_no, user_id, amount, order_status, platform_pay_status
FROM life_payment_orders
WHERE id IN (1,3,4,9,11,14,17,23,27,28)
ORDER BY id;

SELECT ref_id AS order_id, user_id, amount, ref_type, remark
FROM wallet_ledger
WHERE ref_type = 'LIFE_PAYMENT_REFUND'
  AND ref_id IN (1,3,4,9,11,14,17,23,27,28)
ORDER BY ref_id;
