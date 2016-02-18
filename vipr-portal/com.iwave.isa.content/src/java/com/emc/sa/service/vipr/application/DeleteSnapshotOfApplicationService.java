/*
 * Copyright (c) 2016 EMC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.application;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.emc.sa.engine.bind.Param;
import com.emc.sa.engine.service.Service;
import com.emc.sa.service.ServiceParams;
import com.emc.sa.service.vipr.ViPRService;
import com.emc.sa.service.vipr.application.tasks.DeleteSnapshotForApplication;
import com.emc.sa.service.vipr.application.tasks.DeleteSnapshotSessionForApplication;
import com.emc.sa.service.vipr.application.tasks.GetBlockSnapshotSessionList;
import com.emc.sa.service.vipr.application.tasks.GetBlockSnapshotSet;
import com.emc.sa.service.vipr.block.BlockStorageUtils;
import com.emc.storageos.model.DataObjectRestRep;
import com.emc.storageos.model.SnapshotList;
import com.emc.storageos.model.block.BlockSnapshotSessionList;
import com.emc.storageos.model.block.NamedVolumesList;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.vipr.client.Tasks;

@Service("DeleteSnapshotOfApplication")
public class DeleteSnapshotOfApplicationService extends ViPRService {

    @Param(ServiceParams.APPLICATION)
    private URI applicationId;

    @Param(ServiceParams.APPLICATION_SNAPSHOT_TYPE)
    private String snapshotType;

    @Param(ServiceParams.APPLICATION_SUB_GROUP)
    protected List<URI> subGroups;

    @Override
    public void execute() throws Exception {

        // get list of volumes in application
        NamedVolumesList volList = getClient().application().getVolumeByApplication(applicationId);

        Map<String, VolumeRestRep> volumeTypes = BlockStorageUtils.getVolumeSystemTypes(volList, subGroups);

        Tasks<? extends DataObjectRestRep> tasks = null;

        for (String type : volumeTypes.keySet()) {
            if (type.equalsIgnoreCase("VMAX3")) {
                BlockSnapshotSessionList snapSessionList = execute(new GetBlockSnapshotSessionList(applicationId, volumeTypes.get(type)
                        .getReplicationGroupInstance()));
                // TODO error if snapSessionList is empty
                tasks = execute(new DeleteSnapshotSessionForApplication(applicationId, snapSessionList.getSnapSessionRelatedResourceList()
                        .get(0).getId()));
            } else {
                SnapshotList snapshotList = execute(new GetBlockSnapshotSet(applicationId, volumeTypes.get(type)
                        .getReplicationGroupInstance()));
                // TODO error if snapshotList is empty
                tasks = execute(new DeleteSnapshotForApplication(applicationId, snapshotList.getSnapList().get(0).getId()));
            }
            addAffectedResources(tasks);
        }
    }
}
