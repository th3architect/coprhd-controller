/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.fullcopy.rp;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockObject;

public class RPFullCopyManagerApiImpl extends AbstractRPFullCopyManagerApiImpl {

    // A reference to a logger.
    private static final Logger logger = LoggerFactory.getLogger(RPFullCopyManagerApiImpl.class);

    public RPFullCopyManagerApiImpl(DbClient dbClient) {
        super(dbClient);
    }

    @Override
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj) {
        return super.getAllSourceObjectsForFullCopyRequest(fcSourceObj);
    }

}
