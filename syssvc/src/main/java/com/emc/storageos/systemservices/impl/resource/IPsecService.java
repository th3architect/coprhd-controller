/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.systemservices.impl.resource;

import com.emc.storageos.model.ipsec.IPsecStatus;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.systemservices.impl.ipsec.IPsecManager;
import org.springframework.beans.factory.annotation.Autowired;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * Web resource class for IPsec
 */
@Path("/ipsec")
public class IPsecService {


    private static final String IPSEC_SERVICE_TYPE = "ipsec";
    private static final String IPSEC_STATUS = "ipsec_status";
    @Autowired
    private IPsecManager ipsecMgr;

    @Autowired
    private AuditLogManager auditMgr;

    /**
     * Rotate the VIPR IPsec Pre-shared key.
     * @return the new version of the key which is used for checking status if needed
     */
    @POST
    @Path("/key")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public String rotateIPsecKey() {
        ipsecMgr.verifyIPsecOpAllowable();
        String version = ipsecMgr.rotateKey();
        auditMgr.recordAuditLog(null, null,
                IPSEC_SERVICE_TYPE,
                OperationTypeEnum.UPDATE_SYSTEM_PROPERTY,
                System.currentTimeMillis(),
                AuditLogManager.AUDITLOG_SUCCESS,
                null, "config_version=" + version);

        return version;
    }

    /**
     * Check the status of IPsec.
     * @return
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN })
    public IPsecStatus getIPsecStatus() {
        return ipsecMgr.checkStatus();
    }

    /**
     * Change IPsec status to enabled/disabled within VDC and across sites.
     *
     * Setting status to disabled is not recommended in production environment, as it
     * will downgrade the security protection level.
     *
     * @param status - valid values [ enabled | disabled ] (case insensitive)
     * @return the new IPsec state
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public String changeIpsecState(@QueryParam("status") String status) {
        ipsecMgr.verifyIPsecOpAllowable();
        String result = ipsecMgr.changeIpsecStatus(status);
        auditMgr.recordAuditLog(null, null,
                IPSEC_SERVICE_TYPE,
                OperationTypeEnum.UPDATE_SYSTEM_PROPERTY,
                System.currentTimeMillis(),
                AuditLogManager.AUDITLOG_SUCCESS,
                null, IPSEC_STATUS + "=" + status);

        return result;
    }
}
