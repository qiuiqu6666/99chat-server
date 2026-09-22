package com.chat99.server.wallet;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 资金定时任务：仅在本进程本地处理 /wallet/** 时加载。
 * 主服 {@code wallet.proxy-enabled=true} 后不再跑充提/红包过期 Job，避免与资金节点双跑。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnProperty(name = "wallet.proxy-enabled", havingValue = "false", matchIfMissing = true)
public @interface ConditionalOnWalletJobs {
}
