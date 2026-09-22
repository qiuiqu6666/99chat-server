package com.chat99.server.lifepayment;

import com.chat99.server.lifepayment.LifePaymentEnums.ServiceType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LifePaymentProviderRepository extends JpaRepository<LifePaymentProvider, Long> {
    Optional<LifePaymentProvider> findByProviderCode(String providerCode);

    @Query("""
        SELECT p FROM LifePaymentProvider p
        WHERE p.serviceType = :serviceType
          AND p.enabled = true
          AND (:cityCode IS NULL OR :cityCode = '' OR p.cityCode = :cityCode)
          AND (:cityName IS NULL OR :cityName = '' OR p.cityName = :cityName)
          AND (:keyword IS NULL OR :keyword = '' OR LOWER(p.providerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(p.providerAlias, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
        ORDER BY p.providerName ASC
        """)
    Page<LifePaymentProvider> search(@Param("serviceType") ServiceType serviceType,
                                     @Param("cityCode") String cityCode,
                                     @Param("cityName") String cityName,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);

    Optional<LifePaymentProvider> findByServiceTypeAndCityCodeAndProviderName(
        ServiceType serviceType, String cityCode, String providerName);

    Optional<LifePaymentProvider> findByServiceTypeAndCityNameAndProviderName(
        ServiceType serviceType, String cityName, String providerName);

    @Query("""
        SELECT p FROM LifePaymentProvider p
        WHERE (:serviceType IS NULL OR p.serviceType = :serviceType)
          AND (:cityCode IS NULL OR :cityCode = '' OR p.cityCode = :cityCode)
          AND (:cityName IS NULL OR :cityName = '' OR p.cityName = :cityName)
          AND (:keyword IS NULL OR :keyword = '' OR LOWER(p.providerName) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(COALESCE(p.providerAlias, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
               OR LOWER(p.providerCode) LIKE LOWER(CONCAT('%', :keyword, '%')))
          AND (:enabled IS NULL OR p.enabled = :enabled)
        ORDER BY p.updatedAt DESC
        """)
    Page<LifePaymentProvider> adminSearch(@Param("serviceType") ServiceType serviceType,
                                          @Param("cityCode") String cityCode,
                                          @Param("cityName") String cityName,
                                          @Param("keyword") String keyword,
                                          @Param("enabled") Boolean enabled,
                                          Pageable pageable);
}
