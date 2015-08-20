/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.fullcopy.rp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.util.NullColumnValueGetter;

public class RPVPlexFullCopyManagerApiImpl extends AbstractRPFullCopyManagerApiImpl {

    // A reference to a logger.
    private static final Logger logger = LoggerFactory.getLogger(RPVPlexFullCopyManagerApiImpl.class);

    public RPVPlexFullCopyManagerApiImpl(DbClient dbClient) {
        super(dbClient);
    }

    @Override
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj) {
        List<BlockObject> fcSourceObjList = new ArrayList<BlockObject>();
        URI cgURI = fcSourceObj.getConsistencyGroup();
        if (!NullColumnValueGetter.isNullURI(cgURI)) {
            BlockConsistencyGroup cg = dbClient.queryObject(BlockConsistencyGroup.class, cgURI);
            // If there is no corresponding native CG for the VPLEX
            // CG, then this is a CG created prior to 2.2 and in this
            // case we want full copies treated like snapshots, which
            // is only create a copy of the passed object.
            if (!cg.checkForType(Types.LOCAL)) {
                fcSourceObjList.add(fcSourceObj);
            } else {
                // If this is not a CG created prior to 2.2, follow the base class implementation
                // for getting all source objects for the full copy request.
                fcSourceObjList = super.getAllSourceObjectsForFullCopyRequest(fcSourceObj);
            }
        } else {
            fcSourceObjList.add(fcSourceObj);
        }

        return fcSourceObjList;
    }

}
