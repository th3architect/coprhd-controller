/*
 * Copyright 2012-2015 iWave Software LLC
 * All Rights Reserved
 */
package com.emc.sa.service.vipr.block.tasks;

import java.net.URI;

import com.emc.sa.service.vipr.tasks.WaitForTasks;
import com.emc.storageos.model.block.CopiesParam;
import com.emc.storageos.model.block.Copy;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.vipr.client.Tasks;

public class FailoverBlockVolume extends WaitForTasks<VolumeRestRep> {
    public static final String REMOTE_TARGET = "remote";
    private URI volumeId;
    private URI failoverTarget;
    private String type;

    public FailoverBlockVolume(URI volumeId, URI failoverTarget) {
        this(volumeId, failoverTarget, "rp");
    }

    public FailoverBlockVolume(URI volumeId, URI failoverTarget, String type) {
        this.volumeId = volumeId;
        this.failoverTarget = failoverTarget;
        this.type = type;
        provideDetailArgs(volumeId, failoverTarget, type);
    }

    @Override
    protected Tasks<VolumeRestRep> doExecute() throws Exception {
        Copy copy = new Copy();
        copy.setType(type);
        copy.setCopyID(failoverTarget);

        CopiesParam param = new CopiesParam();
        param.getCopies().add(copy);
        return getClient().blockVolumes().failover(volumeId, param);
    }
}
