/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.api.service.impl.resource.fullcopy;


import java.net.URI;
import java.util.List;

import com.emc.storageos.api.service.impl.placement.Scheduler;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.svcs.errorhandling.resources.APIException;

/**
 * The RecoverPoint storage system implementation for the block full copy API.
 */
public class RPBlockFullCopyApiImpl extends AbstractBlockFullCopyApiImpl {
    
    /**
     * Constructor
     * 
     * @param dbClient A reference to a database client.
     * @param coordinator A reference to the coordinator client.
     * @param scheduler A reference to a scheduler. 
     */
    public RPBlockFullCopyApiImpl(DbClient dbClient, CoordinatorClient coordinator,
        Scheduler scheduler) {
        super(dbClient, coordinator, scheduler);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateFullCopyCreateRequest(List<BlockObject> fcSourceObjList, int count) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList create(List<BlockObject> fcSourceObjList, VirtualArray varray,
        String name, boolean createInactive, int count, String taskId) {
        throw APIException.methodNotAllowed.notSupportedForRP();        
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList activate(BlockObject fcSourceObj, Volume fullCopyVolume) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList detach(BlockObject fcSourceObj, Volume fullCopyVolume) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList restoreSource(Volume sourceVolume, Volume fullCopyVolume) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList resynchronizeCopy(Volume sourceVolume, Volume fullCopyVolume) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VolumeRestRep checkProgress(URI sourceURI, Volume fullCopyVolume) {
        throw APIException.methodNotAllowed.notSupportedForRP();
    }
}
