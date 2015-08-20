/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.fullcopy;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.emc.storageos.api.service.impl.placement.Scheduler;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager.FullCopyImpl;
import com.emc.storageos.api.service.impl.resource.fullcopy.rp.RPFullCopyManagerApi;
import com.emc.storageos.api.service.impl.resource.fullcopy.rp.RPVPlexFullCopyManagerApiImpl;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.svcs.errorhandling.resources.APIException;

/**
 * The RecoverPoint storage system implementation for the block full copy API.
 */
public class RPBlockFullCopyApiImpl extends AbstractBlockFullCopyApiImpl {

    // The supported block full copy API implementations
    private Map<String, BlockFullCopyApi> fullCopyImpls = new HashMap<String, BlockFullCopyApi>();

    /**
     * Constructor
     *
     * @param dbClient A reference to a database client.
     * @param coordinator A reference to the coordinator client.
     * @param scheduler A reference to a scheduler.
     */
    public RPBlockFullCopyApiImpl(DbClient dbClient, CoordinatorClient coordinator,
            Scheduler scheduler, Map<String, BlockFullCopyApi> fullCopyImpls) {
        super(dbClient, coordinator, scheduler);
        this.fullCopyImpls = fullCopyImpls;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<BlockObject> getAllSourceObjectsForFullCopyRequest(BlockObject fcSourceObj) {
        // Treats full copies of snapshots as is done in base class.
        if (URIUtil.isType(fcSourceObj.getId(), BlockSnapshot.class)) {
            return super.getAllSourceObjectsForFullCopyRequest(fcSourceObj);
        }

        RPFullCopyManagerApi rpFullCopyManager = getRPFullCopyManagerImpl(fcSourceObj);
        return rpFullCopyManager.getAllSourceObjectsForFullCopyRequest(fcSourceObj);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validateFullCopyCreateRequest(List<BlockObject> fcSourceObjList, int count) {
        if (fcSourceObjList != null && !fcSourceObjList.isEmpty()) {
            BlockObject fcSourceObj = fcSourceObjList.get(0);
            BlockFullCopyApi fullCopyApi = getPlatformSpecificFullCopyImpl(fullCopyImpls, fcSourceObj);
            // Call the appropriate BlockFullCopyApi to perform the validation. In this case, the
            // source object can be a RP protected block volume or VPlex volume. The appropriate
            // VPlex validation or Block validation needs to be called.
            fullCopyApi.validateFullCopyCreateRequest(fcSourceObjList, count);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TaskList create(List<BlockObject> fcSourceObjList, VirtualArray varray,
            String name, boolean createInactive, int count, String taskId) {
        TaskList tasks = null;
        if (fcSourceObjList != null && !fcSourceObjList.isEmpty()) {
            BlockObject fcSourceObj = fcSourceObjList.get(0);
            BlockFullCopyApi fullCopyApi = getPlatformSpecificFullCopyImpl(fullCopyImpls, fcSourceObj);
            // Call the appropriate BlockFullCopyApi to perform the create.
            tasks = fullCopyApi.create(fcSourceObjList, varray, name, createInactive, count, taskId);
        }
        return tasks;
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

    /**
     * Get the correct RPFullCopyManager corresponding the full copy source object.
     *
     * @param fcSourceObj the full copy source object.
     * @return the RPFullCopyManager.
     */
    private RPFullCopyManagerApi getRPFullCopyManagerImpl(BlockObject fcSourceObj) {
        RPFullCopyManagerApi rpFullCopyManager = null;
        VirtualPool vpool = BlockFullCopyUtils.queryFullCopySourceVPool(fcSourceObj, _dbClient);
        if (VirtualPool.vPoolSpecifiesHighAvailability(vpool)) {
            rpFullCopyManager = new RPVPlexFullCopyManagerApiImpl(_dbClient);
        }

        return rpFullCopyManager;
    }

    /**
     * Determines and returns the platform specific full copy implementation. This is almost
     * a full duplication of the code that exists in BlockFullCopyManager. The only difference
     * here is the RP option is omitted here.
     *
     * @param fcSourceObj A reference to the full copy source.
     *
     * @return The platform specific full copy implementation
     */
    protected BlockFullCopyApi getPlatformSpecificFullCopyImpl(Map<String, BlockFullCopyApi> fullCopyImpls, BlockObject fcSourceObj) {

        BlockFullCopyApi fullCopyApi = null;
        VirtualPool vpool = BlockFullCopyUtils.queryFullCopySourceVPool(fcSourceObj, _dbClient);
        if (VirtualPool.vPoolSpecifiesHighAvailability(vpool)) {
            fullCopyApi = fullCopyImpls.get(FullCopyImpl.vplex.name());
        } else {
            URI systemURI = fcSourceObj.getStorageController();
            StorageSystem system = _dbClient.queryObject(StorageSystem.class, systemURI);
            String systemType = system.getSystemType();
            if (DiscoveredDataObject.Type.vmax.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.vmax.name());
                if (system.checkIfVmax3()) {
                    fullCopyApi = fullCopyImpls.get(FullCopyImpl.vmax3.name());
                }
            } else if (DiscoveredDataObject.Type.vnxblock.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.vnx.name());
            } else if (DiscoveredDataObject.Type.vnxe.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.vnxe.name());
            } else if (DiscoveredDataObject.Type.hds.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.hds.name());
            } else if (DiscoveredDataObject.Type.openstack.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.openstack.name());
            } else if (DiscoveredDataObject.Type.scaleio.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.scaleio.name());
            } else if (DiscoveredDataObject.Type.xtremio.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.xtremio.name());
            } else if (DiscoveredDataObject.Type.ibmxiv.name().equals(systemType)) {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.xiv.name());
            } else {
                fullCopyApi = fullCopyImpls.get(FullCopyImpl.dflt.name());
            }
        }

        return fullCopyApi;
    }
}
