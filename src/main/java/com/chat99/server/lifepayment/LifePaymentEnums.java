package com.chat99.server.lifepayment;

public final class LifePaymentEnums {

    private LifePaymentEnums() {}

    public enum ServiceType {
        mobile, water, electric, gas;

        public boolean isUtility() {
            return this == water || this == electric || this == gas;
        }

        public static ServiceType require(String raw) {
            if (raw == null || raw.isBlank()) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT", "service_type required");
            }
            try {
                return ServiceType.valueOf(raw.trim().toLowerCase());
            } catch (IllegalArgumentException e) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT", "unsupported service_type");
            }
        }

        public static ServiceType requireUtility(String raw) {
            ServiceType t = require(raw);
            if (!t.isUtility()) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT", "service_type must be water/electric/gas");
            }
            return t;
        }
    }

    public enum PayMethod {
        coin_99, usdt;

        public static PayMethod require(String raw) {
            if (raw == null || raw.isBlank()) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT", "pay_method required");
            }
            String v = raw.trim().toLowerCase();
            if ("coin_99".equals(v) || "99".equals(v) || "platform".equals(v)) {
                return coin_99;
            }
            if ("usdt".equals(v)) {
                return usdt;
            }
            throw LifePaymentExceptions.badRequest("INVALID_INPUT", "unsupported pay_method");
        }
    }

    public enum PlatformPayStatus {
        pending, paid, failed, refunded
    }

    public enum PluginStatus {
        ready,
        running,
        query_success,
        query_failed,
        cashier_confirm,
        paid_success,
        processing,
        waiting_owner_last_char,
        account_not_found,
        provider_not_found,
        city_not_supported,
        insufficient_balance,
        payment_failed,
        network_error,
        page_unknown,
        need_manual,
        failed,
        cancelled
    }

    public enum OrderStatus {
        created,
        paid,
        running,
        success,
        processing,
        failed,
        need_manual,
        need_owner_last_char,
        cashier_confirm,
        cancelled
    }

    public enum TaskStatus {
        ready, running, success, failed, need_manual, cancelled
    }

    public enum TaskAction {
        query, pay, recharge;

        public static TaskAction require(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return TaskAction.valueOf(raw.trim().toLowerCase());
            } catch (IllegalArgumentException e) {
                throw LifePaymentExceptions.badRequest("INVALID_INPUT", "unsupported task_action");
            }
        }
    }

    public enum QueryStatus {
        ready, running, query_success, query_failed, expired, confirmed
    }

    public enum WorkerStatus {
        online, offline, busy, disabled
    }

    public enum ActorType {
        user, admin, worker, system
    }
}
