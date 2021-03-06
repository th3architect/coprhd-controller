/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.auth.service.impl.resource;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

import com.emc.storageos.security.password.InvalidLoginManager;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.auth.AuthenticationManager;

/**
 * internal resource to tell the authsvc
 * to reload certain system properties from zookeeper
 */
@Path("/internal/reloadAuthsvcProperty")
public class ReloadAuthsvcPropertyResource {

    @Autowired
    protected InvalidLoginManager _invalidLoginManager;

    @Autowired
    protected AuthenticationManager _authManager;

    @POST
    public Response reload() {
        _invalidLoginManager.loadParameterFromZK();

        // for ldap_connection_timeout change:
        //  * need to reload AuthManager, which refresh all cached LdapContextSource
        _authManager.reload();

        return Response.ok().build();
    }
}
