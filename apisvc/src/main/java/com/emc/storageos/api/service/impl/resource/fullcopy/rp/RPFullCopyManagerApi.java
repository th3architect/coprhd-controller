/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.fullcopy.rp;

import java.util.List;

import com.emc.storageos.db.client.model.BlockObject;

public interface RPFullCopyManagerApi {
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj);
}
