/*
 *                       ######
 *                       ######
 * ############    ####( ######  #####. ######  ############   ############
 * #############  #####( ######  #####. ######  #############  #############
 *        ######  #####( ######  #####. ######  #####  ######  #####  ######
 * ###### ######  #####( ######  #####. ######  #####  #####   #####  ######
 * ###### ######  #####( ######  #####. ######  #####          #####  ######
 * #############  #############  #############  #############  #####  ######
 *  ############   ############  #############   ############  #####  ######
 *                                      ######
 *                               #############
 *                               ############
 *
 * Adyen Mirakl Connector
 *
 * Copyright (c) 2019 Adyen B.V.
 * This file is open source and available under the MIT license.
 * See the LICENSE file for more info.
 */

package com.adyen.mirakl.service.dto;

import com.adyen.model.marketpay.DocumentDetail;
import com.mirakl.client.mmp.domain.shop.document.MiraklShopDocument;

public class DocumentDTO {

    private MiraklShopDocument miraklShopDocument;
    private DocumentDetail.DocumentTypeEnum documentTypeEnum;

    public MiraklShopDocument getMiraklShopDocument() {
        return miraklShopDocument;
    }

    public void setMiraklShopDocument(final MiraklShopDocument miraklShopDocument) {
        this.miraklShopDocument = miraklShopDocument;
    }

    public DocumentDetail.DocumentTypeEnum getDocumentTypeEnum() {
        return documentTypeEnum;
    }

    public void setDocumentTypeEnum(final DocumentDetail.DocumentTypeEnum documentTypeEnum) {
        this.documentTypeEnum = documentTypeEnum;
    }
}
