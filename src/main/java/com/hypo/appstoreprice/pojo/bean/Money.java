package com.hypo.appstoreprice.pojo.bean;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * money
 *
 * @author hypo
 * @date 2025-09-16
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Money {

    /**
     * area
     */
    private String area;

    /**
     * area name
     */
    private String areaName;

    /**
     * currency
     */
    private String currency;

    /**
     * currency code
     */
    private String currencyCode;

    /**
     * locale
     */
    private String locale;

    /**
     * price
     */
    private BigDecimal price;

    /**
     * cny price
     */
    private BigDecimal cnyPrice;

}
