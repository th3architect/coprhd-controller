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
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.Volume.PersonalityTypes;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.protectioncontroller.impl.recoverpoint.RPHelper;

public class AbstractRPFullCopyManagerApiImpl implements RPFullCopyManagerApi {

    // A reference to a database client.
    protected DbClient dbClient;
    protected RPHelper rpHelper;

    // A reference to a logger.
    private static final Logger logger = LoggerFactory.getLogger(AbstractRPFullCopyManagerApiImpl.class);

    /**
     * Constructor.
     *
     * @param dbClient A reference to a database client.
     */
    public AbstractRPFullCopyManagerApiImpl(DbClient dbClient) {
        this.dbClient = dbClient;
        rpHelper = new RPHelper();
        rpHelper.setDbClient(dbClient);
    }

    @Override
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj) {
        Volume volume = (Volume) fcSourceObj;

        List<BlockObject> fcSourceObjList = new ArrayList<BlockObject>();
        URI cgURI = fcSourceObj.getConsistencyGroup();
        if (!NullColumnValueGetter.isNullURI(cgURI)) {
            if (NullColumnValueGetter.isNotNullValue(volume.getPersonality())) {
                if (volume.getPersonality().equals(PersonalityTypes.SOURCE.name())) {
                    logger.info(String.format("Getting all source full copy objects for the requested RP source volume %s.", volume.getId()
                            .toString()));
                    // If the requested volume is an RP source volume, we want to create a full
                    // copy for each of the source volumes in the CG.
                    fcSourceObjList.addAll(rpHelper.getCgVolumes(cgURI, PersonalityTypes.SOURCE.name()));
                } else if (volume.getPersonality().equals(PersonalityTypes.TARGET.name())) {
                    logger.info(String.format("Getting all source full copy objects for the requested RP target volume %s.", volume.getId()
                            .toString()));
                    // In the case where the requested volume is a target volume, we only want to
                    // create a full copy for the requested volume.
                    fcSourceObjList.add(fcSourceObj);
                }
            }
        }

        return fcSourceObjList;
    }

}
