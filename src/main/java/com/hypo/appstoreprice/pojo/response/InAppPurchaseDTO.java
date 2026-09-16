package com.hypo.appstoreprice.pojo.response;

import com.hypo.appstoreprice.pojo.bean.Money;
import lombok.Data;


/**
 * in-app purchase dto
 *
 * @author hypo
 * @date 2025-09-16
 */
@Data
public class InAppPurchaseDTO {

    /**
     * object
     */
    private String object;

    /**
     * price
     */
    private Money price;

}
