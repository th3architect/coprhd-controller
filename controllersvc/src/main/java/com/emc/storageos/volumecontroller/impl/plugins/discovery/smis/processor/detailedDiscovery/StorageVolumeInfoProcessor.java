/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2008-2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */
package com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.processor.detailedDiscovery;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.cim.CIMDataType;
import javax.cim.CIMInstance;
import javax.cim.CIMObjectPath;
import javax.cim.CIMProperty;
import javax.cim.UnsignedInteger32;
import javax.wbem.CloseableIterator;
import javax.wbem.client.EnumerateResponse;
import javax.wbem.client.WBEMClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedExportMask;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeCharacterstics;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.plugins.common.PartitionManager;
import com.emc.storageos.plugins.common.domainmodel.Operation;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.plugins.discovery.smis.processor.StorageProcessor;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.utils.DiscoveryUtils;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;

/**
 * Processor used in updating information on preExisting Volumes
 * 
 */
public class StorageVolumeInfoProcessor extends StorageProcessor {
    private Logger              _logger                              = LoggerFactory
                                                                             .getLogger(StorageVolumeInfoProcessor.class);
    private List<Object>        _args;
    private DbClient            _dbClient;
    private static final String SVUSAGE                              = "SVUsage";
    private static final String USAGE                                = "Usage";
    private static final String TWELVE                               = "12";
    private static final String TWO                                  = "2";
    private static final String NINE                                 = "9";
    private static final String SEVEN                                = "7";
    private static final String ELEVEN                               = "11";
    private static final String TRUE                                 = "true";
    private static final String FALSE                                = "false";
    private static final String UNMANAGED_VOLUME                     = "UnManagedVolume";
    private static final String UNMANAGED_EXPORT_MASK                = "UnManagedExportMask";
    private static final String SVELEMENT_NAME                       = "SVElementName";
    private static final String NAME                                 = "Name";
    private static final String THINLY_PROVISIONED                   = "ThinlyProvisioned";
    private AccessProfile       _profile;

    private PartitionManager    _partitionManager;

    List<UnManagedVolume>       _unManagedVolumesInsert              = null;
    List<UnManagedVolume>       _unManagedVolumesUpdate              = null;
    List<UnManagedExportMask>   _unManagedExportMasksUpdate          = null;
    List<CIMObjectPath>         _metaVolumeViewPaths                 = null;
    Set<URI>                    unManagedVolumesReturnedFromProvider = new HashSet<URI>();

    public void setPartitionManager(PartitionManager partitionManager) {
        _partitionManager = partitionManager;
    }

    @Override
    public void processResult(Operation operation, Object resultObj, Map<String, Object> keyMap)
            throws BaseCollectionException {
        CloseableIterator<CIMInstance> volumeInstances = null;
        EnumerateResponse<CIMInstance> volumeInstanceChunks = null;
        CIMObjectPath storagePoolPath = null;
        WBEMClient client = null;
        try {
            _dbClient = (DbClient) keyMap.get(Constants.dbClient);
            client = (WBEMClient) keyMap.get(Constants._cimClient);
            _profile = (AccessProfile) keyMap.get(Constants.ACCESSPROFILE);
            Map<String, VolHostIOObject> exportedVolumes = (Map<String, VolHostIOObject>) keyMap
                    .get(Constants.EXPORTED_VOLUMES);
            Set<String> existingVolumesInCG = (Set<String>) keyMap.get(Constants.VOLUMES_PART_OF_CG);
            @SuppressWarnings("unchecked")
            Map<String, RemoteMirrorObject> volumeToRAGroupMap = (Map<String, RemoteMirrorObject>) keyMap
                    .get(Constants.UN_VOLUME_RAGROUP_MAP);
            @SuppressWarnings("unchecked")
            Map<String, LocalReplicaObject> volumeToLocalReplicaMap = (Map<String, LocalReplicaObject>) keyMap.get(Constants.UN_VOLUME_LOCAL_REPLICA_MAP);

            storagePoolPath = getObjectPathfromCIMArgument(_args);
            String poolNativeGuid = NativeGUIDGenerator.generateNativeGuidForPool(storagePoolPath);
            StoragePool pool = checkStoragePoolExistsInDB(poolNativeGuid, _dbClient);
            if (pool == null) {
                _logger.error(
                        "Skipping unmanaged volume discovery as the storage pool with path {} doesn't exist in ViPR",
                        storagePoolPath.toString());
                return;
            }
            _unManagedVolumesInsert = new ArrayList<UnManagedVolume>();
            _unManagedVolumesUpdate = new ArrayList<UnManagedVolume>();
            _unManagedExportMasksUpdate = new ArrayList<UnManagedExportMask>();

            Set<String> poolSupportedSLONames = (Set<String>) keyMap.get(poolNativeGuid);
            _logger.debug("Pool Supporting SLO Names:{}", poolSupportedSLONames);
            _metaVolumeViewPaths = (List<CIMObjectPath>) keyMap.get(Constants.META_VOLUMES_VIEWS);
            if (_metaVolumeViewPaths == null) {
                _metaVolumeViewPaths = new ArrayList<CIMObjectPath>();
                keyMap.put(Constants.META_VOLUMES_VIEWS, _metaVolumeViewPaths);
            }
            // create empty place holder list for meta volume paths (cannot
            // define this in xml)
            List<CIMObjectPath> metaVolumePaths = (List<CIMObjectPath>) keyMap.get(Constants.META_VOLUMES);
            if (metaVolumePaths == null) {
                keyMap.put(Constants.META_VOLUMES, new ArrayList<CIMObjectPath>());
            }

            // get VolumeInfo Object and inject Fast Policy Name.

            volumeInstanceChunks = (EnumerateResponse<CIMInstance>) resultObj;
            volumeInstances = volumeInstanceChunks.getResponses();

            processVolumes(volumeInstances, keyMap, operation, pool, exportedVolumes,
                    existingVolumesInCG, volumeToRAGroupMap, volumeToLocalReplicaMap, poolSupportedSLONames);
            while (!volumeInstanceChunks.isEnd()) {
                _logger.info("Processing Next Volume Chunk of size {}", BATCH_SIZE);
                volumeInstanceChunks = client.getInstancesWithPath(storagePoolPath,
                        volumeInstanceChunks.getContext(), new UnsignedInteger32(BATCH_SIZE));
                processVolumes(volumeInstanceChunks.getResponses(), keyMap, operation, pool, exportedVolumes,
                        existingVolumesInCG, volumeToRAGroupMap, volumeToLocalReplicaMap, poolSupportedSLONames);
            }
            if (null != _unManagedVolumesUpdate && _unManagedVolumesUpdate.size() > 0) {
                _partitionManager.updateInBatches(_unManagedVolumesUpdate, getPartitionSize(keyMap),
                        _dbClient, UNMANAGED_VOLUME);
            }

            if (null != _unManagedVolumesInsert && _unManagedVolumesInsert.size() > 0) {
                _partitionManager.insertInBatches(_unManagedVolumesInsert, getPartitionSize(keyMap),
                        _dbClient, UNMANAGED_VOLUME);
            }

            if (null != _unManagedExportMasksUpdate && _unManagedExportMasksUpdate.size() > 0) {
                _partitionManager.updateInBatches(_unManagedExportMasksUpdate, getPartitionSize(keyMap),
                        _dbClient, UNMANAGED_EXPORT_MASK);
            }

            performStorageUnManagedVolumeBookKeeping(pool.getId());

        } catch (Exception e) {
            _logger.error("Processing Storage Volume Information failed :", e);
        } finally {
            _unManagedVolumesInsert = null;
            _unManagedVolumesUpdate = null;
            if (null != volumeInstances) {
                volumeInstances.close();
            }
            if (null != volumeInstanceChunks) {
                try {
                    client.closeEnumeration(storagePoolPath, volumeInstanceChunks.getContext());
                } catch (Exception e) {
                }
            }

        }
    }

    /**
     * Process the volumes to find the unmanaged volumes and populate the volume
     * supported information.
     * 
     * @param it
     * @param keyMap
     * @param operation
     * @param pool
     * @param exportedVolumes
     * @param volumesAndReplicas
     * @param existingVolumesInCG
     * @param volumeToRAGroupMap
     * @param poolSupportedSLONames
     */
    private void processVolumes(Iterator<CIMInstance> it, Map<String, Object> keyMap, Operation operation,
            StoragePool pool, Map<String, VolHostIOObject> exportedVolumes,
            Set<String> existingVolumesInCG, Map<String, RemoteMirrorObject> volumeToRAGroupMap,
            Map<String, LocalReplicaObject> volumeToLocalReplicaMap,
            Set<String> poolSupportedSLONames) {

        List<CIMObjectPath> metaVolumes = new ArrayList<CIMObjectPath>();
        while (it.hasNext()) {
            CIMInstance volumeViewInstance = null;
            try {
                volumeViewInstance = it.next();
                String volumeNativeGuid = getVolumeViewNativeGuid(volumeViewInstance.getObjectPath(), keyMap);

                Volume volume = checkStorageVolumeExistsInDB(volumeNativeGuid, _dbClient);
                if (null != volume) {
                    _logger.debug("Skipping discovery, as this Volume {} is already being managed by ViPR.",
                            volumeNativeGuid);
                    continue;
                }

                addPath(keyMap, operation.get_result(), volumeViewInstance.getObjectPath());
                String unManagedVolumeNativeGuid = getUnManagedVolumeNativeGuid(
                        volumeViewInstance.getObjectPath(), keyMap);

                UnManagedVolume unManagedVolume = checkUnManagedVolumeExistsInDB(unManagedVolumeNativeGuid,
                        _dbClient);
                unManagedVolume = createUnManagedVolume(unManagedVolume, volumeViewInstance,
                        unManagedVolumeNativeGuid, pool, _profile.getSystemId(), volumeNativeGuid,
                        exportedVolumes, existingVolumesInCG, volumeToRAGroupMap,
                        volumeToLocalReplicaMap, poolSupportedSLONames, keyMap);

                // set up UnManagedExportMask information

                @SuppressWarnings("unchecked")
                Map<String, Set<UnManagedExportMask>> masksMap = (Map<String, Set<UnManagedExportMask>>) keyMap
                        .get(Constants.UNMANAGED_EXPORT_MASKS_MAP);
                if (masksMap != null) {

                    Set<UnManagedExportMask> uems = masksMap.get(unManagedVolume.getNativeGuid());
                    if (uems != null) {
                        _logger.info("{} UnManagedExportMasks found in the keyMap for volume {}",
                                uems.size(), unManagedVolume.getNativeGuid());
                        for (UnManagedExportMask uem : uems) {
                            _logger.info("   adding UnManagedExportMask {} to UnManagedVolume",
                                    uem.getMaskingViewPath());
                            unManagedVolume.getUnmanagedExportMasks().add(uem.getId().toString());
                            uem.getUnmanagedVolumeUris().add(unManagedVolume.getId().toString());
                            _unManagedExportMasksUpdate.add(uem);

                            // add the known initiators, too
                            for (String initUri : uem.getKnownInitiatorUris()) {
                                _logger.info("   adding known Initiator URI {} to UnManagedVolume", initUri);
                                unManagedVolume.getInitiatorUris().add(initUri);
                                Initiator init = _dbClient.queryObject(Initiator.class, URI.create(initUri));
                                unManagedVolume.getInitiatorNetworkIds().add(init.getInitiatorPort());
                            }

                            // log this info for debugging
                            for (String path : uem.getUnmanagedInitiatorNetworkIds()) {
                                _logger.info("   UnManagedExportMask has this initiator unknown to ViPR: {}",
                                        path);
                            }
                            
                            // check if this volume is in a vplex backend mask
                            // and mark it as such if it is
                            Object o = keyMap.get(Constants.UNMANAGED_VPLEX_BACKEND_MASKS_SET);
                            if (o != null) {
                                Set<String> unmanagedVplexBackendMasks = (Set<String>) o;
                                if (unmanagedVplexBackendMasks.size() > 0) {
                                    if (unmanagedVplexBackendMasks.contains(uem.getId().toString())) {
                                        _logger.info("unmanaged volume {} is a vplex backend volume", unManagedVolume.getLabel());
                                        unManagedVolume.putVolumeCharacterstics(
                                                SupportedVolumeCharacterstics.IS_VPLEX_BACKEND_VOLUME.toString(),
                                                "true");
                                    }
                                }
                            }
                        }
                    }
                }
                _logger.debug(
                        "Going to check if the volume is meta: {}, volume meta property: {}",
                        volumeViewInstance.getObjectPath(),
                        unManagedVolume.getVolumeCharacterstics().get(
                                SupportedVolumeCharacterstics.IS_METAVOLUME.toString()));
                // Check if the volume is meta volume and add it to the meta
                // volume list
                String isMetaVolume = unManagedVolume.getVolumeCharacterstics().get(
                        SupportedVolumeCharacterstics.IS_METAVOLUME.toString());
                if (null != isMetaVolume && Boolean.valueOf(isMetaVolume)) {
                    metaVolumes.add(volumeViewInstance.getObjectPath());
                    _logger.info("Found meta volume: {}, name: {}", volumeViewInstance.getObjectPath(),
                            unManagedVolume.getLabel());
                }

                // if volumes size reaches 200 , then dump to Db.
                if (_unManagedVolumesInsert.size() > BATCH_SIZE) {
                    _partitionManager.insertInBatches(_unManagedVolumesInsert, getPartitionSize(keyMap),
                            _dbClient, UNMANAGED_VOLUME);
                    _unManagedVolumesInsert.clear();
                }

                if (_unManagedVolumesUpdate.size() > BATCH_SIZE) {
                    _partitionManager.updateInBatches(_unManagedVolumesUpdate, getPartitionSize(keyMap),
                            _dbClient, UNMANAGED_VOLUME);
                    _unManagedVolumesUpdate.clear();
                }

                if (_unManagedExportMasksUpdate.size() > BATCH_SIZE) {
                    _partitionManager.updateInBatches(_unManagedExportMasksUpdate, getPartitionSize(keyMap),
                            _dbClient, UNMANAGED_EXPORT_MASK);
                    _unManagedExportMasksUpdate.clear();
                }

                unManagedVolumesReturnedFromProvider.add(unManagedVolume.getId());

            } catch (Exception ex) {
                _logger.error("Processing UnManaged Storage Volume {} ", volumeViewInstance.getObjectPath(),
                        ex);
            }
        }

        // Add meta volumes to the keyMap
        try {
            if (metaVolumes != null && !metaVolumes.isEmpty()) {
                _metaVolumeViewPaths.addAll(metaVolumes);
                _logger.info("Added  {} meta volumes.", metaVolumes.size());
            }
        } catch (Exception ex) {
            _logger.error("Processing UnManaged meta volumes.", ex);

        }
    }

    /**
     * This method translates volume view cim object path to storage volume
     * path. Can be used as an alternative to smi-s call to get storage volume
     * path for volume view, in case there is feasible performance penalty for
     * using smi-s call for each meta volume view.
     *
     * Example: from: //10.247.99.71/root/emc:Symm_VolumeView.SPInstanceID=
     * "SYMMETRIX+000195701573+C+0005",SVCreationClassName="Symm_StorageVolume",
     * SVDeviceID
     * ="004A5",SVSystemCreationClassName="Symm_StorageSystem",SVSystemName
     * ="SYMMETRIX+000195701573"; };
     *
     * to: //10.247.99.71/root/emc:Symm_StorageVolume.CreationClassName=
     * "Symm_StorageVolume"
     * ,DeviceID="004A5",SystemCreationClassName="Symm_StorageSystem",
     * SystemName="SYMMETRIX+000195701573"
     * 
     * @param volumeViewPath
     * @return storageVolume path
     */
    public static CIMObjectPath translateToStorageVolumePath(CIMObjectPath volumeViewPath) {

        String creationClassName = volumeViewPath.getKey(SVCREATIONCLASSNAME).getValue().toString();
        String systemCreationClassName = volumeViewPath.getKey(SVSYSTEMCREATIONCLASSNAME).getValue()
                .toString();
        ;
        String systemName = volumeViewPath.getKey(SVSYSTEMNAME).getValue().toString();
        String id = volumeViewPath.getKey(SVDEVICEID).getValue().toString();
        String host = volumeViewPath.getHost();
        String namespace = volumeViewPath.getNamespace();
        String objectTypeName = creationClassName;
        CIMProperty[] volumeKeys = {
                new CIMProperty<String>(SmisConstants.CP_CREATION_CLASS_NAME, CIMDataType.STRING_T,
                        creationClassName, true, false, null),
                new CIMProperty<String>(SmisConstants.CP_DEVICE_ID, CIMDataType.STRING_T, id, true, false,
                        null),
                new CIMProperty<String>(SmisConstants.CP_SYSTEM_CREATION_CLASS_NAME, CIMDataType.STRING_T,
                        systemCreationClassName, true, false, null),
                new CIMProperty<String>(SmisConstants.CP_SYSTEM_NAME, CIMDataType.STRING_T, systemName, true,
                        false, null), };

        return new CIMObjectPath(null, host, null, namespace, objectTypeName, volumeKeys);
    }

    /**
     * This method cleans up UnManaged Volumes in DB, which had been deleted
     * manually from the Array 1. Get All UnManagedVolumes from DB 2. Store URIs
     * of unmanaged volumes returned from the Provider in
     * unManagedVolumesBookKeepingList. 3. If unmanaged volume is found only in
     * DB, but not in unManagedVolumesBookKeepingList, then set unmanaged volume
     * to inactive.
     * 
     * DB | Provider
     * 
     * x,y,z | y,z.a [a --> new entry has been added but indexes didn't get
     * added yet into DB]
     * 
     * x--> will be set to inactive
     * 
     * 
     * 
     * @param storagePoolUri
     * @throws IOException
     */
    private void performStorageUnManagedVolumeBookKeeping(URI storagePoolUri) throws IOException {
        @SuppressWarnings("deprecation")
        List<URI> unManagedVolumesInDB = _dbClient.queryByConstraint(ContainmentConstraint.Factory
                .getPoolUnManagedVolumeConstraint(storagePoolUri));

        Set<URI> unManagedVolumesInDBSet = new HashSet<URI>(unManagedVolumesInDB);
        SetView<URI> onlyAvailableinDB = Sets.difference(unManagedVolumesInDBSet,
                unManagedVolumesReturnedFromProvider);

        _logger.info("Diff :" + Joiner.on("\t").join(onlyAvailableinDB));
        if (onlyAvailableinDB.size() > 0) {
            List<UnManagedVolume> unManagedVolumeTobeDeleted = new ArrayList<UnManagedVolume>();
            Iterator<UnManagedVolume> unManagedVolumes = _dbClient.queryIterativeObjects(
                    UnManagedVolume.class, new ArrayList<URI>(onlyAvailableinDB));

            while (unManagedVolumes.hasNext()) {
                UnManagedVolume volume = unManagedVolumes.next();
                if (null == volume || volume.getInactive()) {
                    continue;
                }

                _logger.info("Setting unManagedVolume {} inactive", volume.getId());
                volume.setStoragePoolUri(NullColumnValueGetter.getNullURI());
                volume.setStorageSystemUri(NullColumnValueGetter.getNullURI());
                volume.setInactive(true);
                unManagedVolumeTobeDeleted.add(volume);
            }
            if (unManagedVolumeTobeDeleted.size() > 0) {
                _partitionManager.updateAndReIndexInBatches(unManagedVolumeTobeDeleted, 1000, _dbClient,
                        "UnManagedVolume");
            }
        }

    }

    /**
     * create StorageVolume Info Object
     * 
     * @param unManagedVolume
     * @param volumeInstance
     * @param unManagedVolumeNativeGuid
     * @param pool
     * @return
     */
    private UnManagedVolume createUnManagedVolume(UnManagedVolume unManagedVolume,
            CIMInstance volumeInstance,
            String unManagedVolumeNativeGuid,
            StoragePool pool,
            URI storageSystemUri,
            String volumeNativeGuid,
            // to make the code uniform, passed in all the below sets as
            // arguments
            Map<String, VolHostIOObject> exportedVolumes,
            Set<String> existingVolumesInCG, Map<String, RemoteMirrorObject> volumeToRAGroupMap,
            Map<String, LocalReplicaObject> volumeToLocalReplicaMap,
            Set<String> poolSupportedSLONames, Map<String, Object> keyMap) {
        _logger.info("Process UnManagedVolume {}", unManagedVolumeNativeGuid);
        try {
            boolean created = false;
            if (null == unManagedVolume) {
                unManagedVolume = new UnManagedVolume();
                unManagedVolume.setId(URIUtil.createId(UnManagedVolume.class));
                unManagedVolume.setNativeGuid(unManagedVolumeNativeGuid);
                unManagedVolume.setStorageSystemUri(storageSystemUri);
                created = true;
            }

            // reset the auto-tiering info for unmanaged volumes already present
            // in db
            // so that the tiering info is updated correctly later
            if (!created) {
                unManagedVolume.getVolumeInformation().put(
                        SupportedVolumeInformation.AUTO_TIERING_POLICIES.toString(), "");
                unManagedVolume.putVolumeCharacterstics(
                        SupportedVolumeCharacterstics.IS_AUTO_TIERING_ENABLED.toString(), "false");

                // reset local replica info
                unManagedVolume.putVolumeCharacterstics(SupportedVolumeCharacterstics.IS_FULL_COPY.name(), FALSE);
                unManagedVolume.putVolumeCharacterstics(SupportedVolumeCharacterstics.IS_LOCAL_MIRROR.name(), FALSE);
                unManagedVolume.putVolumeCharacterstics(SupportedVolumeCharacterstics.IS_SNAP_SHOT.name(), FALSE);
                unManagedVolume.putVolumeCharacterstics(SupportedVolumeCharacterstics.HAS_REPLICAS.name(), FALSE);

                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.REPLICA_STATE.name(), new StringSet());

                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.SYNC_STATE.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.SYNC_TYPE.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.SYNCHRONIZED_INSTANCE.name(), new StringSet());

                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.IS_SYNC_ACTIVE.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.NEEDS_COPY_TO_TARGET.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.TECHNOLOGY_TYPE.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.SETTINGS_INSTANCE.name(), new StringSet());

                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.FULL_COPIES.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.MIRRORS.name(), new StringSet());
                unManagedVolume.getVolumeInformation().put(SupportedVolumeInformation.SNAPSHOTS.name(), new StringSet());
            }

            Map<String, StringSet> unManagedVolumeInformation = new HashMap<String, StringSet>();
            Map<String, String> unManagedVolumeCharacteristics = new HashMap<String, String>();

            StorageSystem system = _dbClient.queryObject(StorageSystem.class, _profile.getSystemId());
            if (null != system) {
                StringSet systemTypes = new StringSet();
                systemTypes.add(system.getSystemType());
                unManagedVolumeInformation
                        .put(SupportedVolumeInformation.SYSTEM_TYPE.toString(), systemTypes);
            }

            if (exportedVolumes != null && exportedVolumes.containsKey(volumeNativeGuid)) {
                VolHostIOObject obj = exportedVolumes.get(volumeNativeGuid);
                if (null != obj) {
                    StringSet bwValues = new StringSet();
                    bwValues.add(obj.getHostIoBw());
                    if (unManagedVolumeInformation.get(SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH
                            .toString()) == null) {
                        unManagedVolumeInformation.put(
                                SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH.toString(), bwValues);
                    } else {
                        unManagedVolumeInformation.get(
                                SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH.toString()).replace(
                                bwValues);
                    }

                    StringSet iopsVal = new StringSet();
                    iopsVal.add(obj.getHostIops());

                    if (unManagedVolumeInformation
                            .get(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString()) == null) {
                        unManagedVolumeInformation.put(
                                SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString(), iopsVal);
                    } else {
                        unManagedVolumeInformation
                                .get(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString()).replace(iopsVal);
                    }
                }
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_VOLUME_EXPORTED.toString(), TRUE);

            } else {
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_VOLUME_EXPORTED.toString(), FALSE);
                StringSet bwValues = new StringSet();
                bwValues.add("0");

                if (unManagedVolumeInformation.get(SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH
                        .toString()) == null) {
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH.toString(), bwValues);
                } else {
                    unManagedVolumeInformation.get(
                            SupportedVolumeInformation.EMC_MAXIMUM_IO_BANDWIDTH.toString()).replace(bwValues);
                }

                StringSet iopsVal = new StringSet();
                iopsVal.add("0");

                if (unManagedVolumeInformation.get(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString()) == null) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString(),
                            iopsVal);
                } else {
                    unManagedVolumeInformation.get(SupportedVolumeInformation.EMC_MAXIMUM_IOPS.toString())
                            .replace(iopsVal);
                }
            }
            
            // Set SLOName only for VMAX3 exported volumes
            if (system.checkIfVmax3()) {
                // If there are no slonames defined for a pool or no slo
                // set for a volume, update the tiering_enabled to false.
                if (poolSupportedSLONames.isEmpty() || !keyMap.containsKey(Constants.VOLUMES_WITH_SLOS)) {
                    unManagedVolumeCharacteristics.put(
                            SupportedVolumeCharacterstics.IS_AUTO_TIERING_ENABLED.toString(),
                            Boolean.FALSE.toString());
                } else {
                    Map<String, String> volumesWithSLO = (Map<String, String>) keyMap.get(Constants.VOLUMES_WITH_SLOS);
                    if (volumesWithSLO.containsKey(volumeNativeGuid)) {
                        String sloName = volumesWithSLO.get(volumeNativeGuid);
                        _logger.debug("formattedSLOName: {}", sloName);
                        updateSLOPolicies(poolSupportedSLONames, unManagedVolumeInformation,
                                unManagedVolumeCharacteristics, sloName);
                    } else {
                        unManagedVolumeCharacteristics.put(
                                SupportedVolumeCharacterstics.IS_AUTO_TIERING_ENABLED.toString(),
                                Boolean.FALSE.toString());
                    }
                }
            }

            if (existingVolumesInCG != null && existingVolumesInCG.contains(volumeNativeGuid)) {
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_VOLUME_ADDED_TO_CONSISTENCYGROUP.toString(), TRUE);
            } else {
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_VOLUME_ADDED_TO_CONSISTENCYGROUP.toString(), FALSE);
            }

            Object raidLevelObj;
            boolean isIngestable;
            String isBound;
            String isThinlyProvisioned;
            // Set the attributes for new smis version.
            if (keyMap.containsKey(Constants.IS_NEW_SMIS_PROVIDER)
                    && Boolean.valueOf(keyMap.get(Constants.IS_NEW_SMIS_PROVIDER).toString())) {
                unManagedVolume.setLabel(getCIMPropertyValue(volumeInstance, NAME));
                raidLevelObj = volumeInstance.getPropertyValue(SupportedVolumeInformation.RAID_LEVEL
                        .getAlternateKey());
                isBound = getCIMPropertyValue(volumeInstance,
                        SupportedVolumeCharacterstics.IS_BOUND.getAlterCharacterstic());
                isIngestable = isVolumeIngestable(volumeInstance, isBound, USAGE);
                isThinlyProvisioned = getCIMPropertyValue(volumeInstance, THINLY_PROVISIONED);
            } else {
                unManagedVolume.setLabel(getCIMPropertyValue(volumeInstance, SVELEMENT_NAME));
                isBound = getCIMPropertyValue(volumeInstance,
                        SupportedVolumeCharacterstics.IS_BOUND.getCharacterstic());
                raidLevelObj = volumeInstance.getPropertyValue(SupportedVolumeInformation.RAID_LEVEL
                        .getInfoKey());
                isIngestable = isVolumeIngestable(volumeInstance, isBound, SVUSAGE);
                isThinlyProvisioned = getCIMPropertyValue(volumeInstance, EMC_THINLY_PROVISIONED);
            }

            if (null != raidLevelObj) {
                StringSet raidLevels = new StringSet();
                raidLevels.add(raidLevelObj.toString());
                unManagedVolumeInformation.put(SupportedVolumeInformation.RAID_LEVEL.toString(), raidLevels);
            }

            if (null != isBound) {
                unManagedVolumeCharacteristics
                        .put(SupportedVolumeCharacterstics.IS_BOUND.toString(), isBound);
            }
            if (null != isThinlyProvisioned) {
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_THINLY_PROVISIONED.toString(), 
                        isThinlyProvisioned);
            }
            // only Volumes with Usage 2 can be ingestable, other volumes
            // [SAVE,VAULT...] apart from replicas have usage other than 2
            // Volumes which are set EMCIsBound as false cannot be ingested
            if (isIngestable) {
                unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.IS_INGESTABLE.toString(),
                        TRUE);
            } else {
                unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.IS_INGESTABLE.toString(),
                        FALSE);
            }

            if (volumeToRAGroupMap.containsKey(unManagedVolume.getNativeGuid())) {
                RemoteMirrorObject rmObj = volumeToRAGroupMap.get(unManagedVolume.getNativeGuid());
                _logger.info("Found RA Object {}", rmObj.toString());
                if (RemoteMirrorObject.Types.SOURCE.toString().equalsIgnoreCase(rmObj.getType())) {
                    _logger.info("Found Source, updating targets {}", rmObj.getTargetVolumenativeGuids());
                    // setting target Volumes
                    if (unManagedVolumeInformation.get(SupportedVolumeInformation.REMOTE_MIRRORS.toString()) == null) {
                        unManagedVolumeInformation.put(SupportedVolumeInformation.REMOTE_MIRRORS.toString(),
                                rmObj.getTargetVolumenativeGuids());
                    } else {
                        if (null == rmObj.getTargetVolumenativeGuids()
                                || rmObj.getTargetVolumenativeGuids().size() == 0) {
                            unManagedVolumeInformation.get(
                                    SupportedVolumeInformation.REMOTE_MIRRORS.toString()).clear();
                        } else {
                            unManagedVolumeInformation.get(
                                    SupportedVolumeInformation.REMOTE_MIRRORS.toString()).replace(
                                    rmObj.getTargetVolumenativeGuids());
                        }
                    }
                } else if (RemoteMirrorObject.Types.TARGET.toString().equalsIgnoreCase(rmObj.getType())) {

                    _logger.info("Found Target {}, updating copyMode {}, RA Group",
                            unManagedVolume.getNativeGuid(), rmObj.getCopyMode());
                    // setting srdfParent
                    StringSet parentVolume = new StringSet();
                    parentVolume.add(rmObj.getSourceVolumeNativeGuid());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.REMOTE_MIRROR_SOURCE_VOLUME.toString(), parentVolume);

                    // setting RAGroup
                    StringSet raGroup = new StringSet();
                    raGroup.add(rmObj.getRaGroupUri().toString());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.REMOTE_MIRROR_RDF_GROUP.toString(), raGroup);

                }
                // setting Copy Modes
                StringSet copyModes = new StringSet();
                copyModes.add(rmObj.getCopyMode());
                if (unManagedVolumeInformation.get(SupportedVolumeInformation.REMOTE_COPY_MODE.toString()) == null) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.REMOTE_COPY_MODE.toString(),
                            copyModes);
                } else {
                    unManagedVolumeInformation.get(SupportedVolumeInformation.REMOTE_COPY_MODE.toString())
                            .replace(copyModes);
                }
                // setting Volume Type

                StringSet volumeType = new StringSet();
                volumeType.add(rmObj.getType());
                unManagedVolumeInformation.put(SupportedVolumeInformation.REMOTE_VOLUME_TYPE.toString(),
                        volumeType);

                unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString(),
                        TRUE);
            } else {
                unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString(),
                        FALSE);
            }

            // handle clones, local mirrors and snapshots
            boolean isLocalReplica = false;
            if (volumeToLocalReplicaMap.containsKey(unManagedVolume
                    .getNativeGuid())) {
                _logger.info("Found in localReplicaMap {}",
                        unManagedVolume.getNativeGuid());
                LocalReplicaObject lrObj = volumeToLocalReplicaMap
                        .get(unManagedVolume.getNativeGuid());
                isLocalReplica = lrObj.isReplica();

                // setting targets
                StringSet fullCopies = lrObj.getFullCopies();
                if (fullCopies != null && !fullCopies.isEmpty()) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.FULL_COPIES.name(), fullCopies);
                }

                StringSet mirrors = lrObj.getMirrors();
                if (mirrors != null && !mirrors.isEmpty()) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.MIRRORS.name(), mirrors);
                }

                StringSet snapshots = lrObj.getSnapshots();
                if (snapshots != null && !snapshots.isEmpty()) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.SNAPSHOTS.name(), snapshots);
                }

                if (lrObj.hasReplica()) {
                    //set the HAS_REPLICAS property
                    unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.HAS_REPLICAS.name(),
                            TRUE);
                }

                if (LocalReplicaObject.Types.FullCopy.equals(lrObj
                        .getType())) {
                    _logger.info("Found Clone {}",
                            unManagedVolume.getNativeGuid());
                    // setting clone specific info
                    unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.IS_FULL_COPY.name(), TRUE);
                    StringSet sourceVolume = new StringSet();
                    sourceVolume.add(lrObj.getSourceNativeGuid());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME
                                    .name(), sourceVolume);

                    StringSet isSyncActive = new StringSet();
                    isSyncActive.add(new Boolean(lrObj.isSyncActive())
                            .toString());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.IS_SYNC_ACTIVE.name(),
                            isSyncActive);

                    StringSet replicaState = new StringSet();
                    replicaState.add(lrObj.getReplicaState());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.REPLICA_STATE
                                    .name(), replicaState);
                } else if (LocalReplicaObject.Types.BlockMirror.equals(lrObj
                        .getType())) {
                    _logger.info("Found Local Mirror {}",
                            unManagedVolume.getNativeGuid());
                    // setting local mirror specific info
                    unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.IS_LOCAL_MIRROR.name(), TRUE);
                    StringSet sourceVolume = new StringSet();
                    sourceVolume.add(lrObj.getSourceNativeGuid());
                    unManagedVolumeInformation
                            .put(SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME
                                    .name(), sourceVolume);

                    StringSet syncState = new StringSet();
                    syncState.add(lrObj.getSyncState());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.SYNC_STATE.name(),
                            syncState);

                    StringSet syncType = new StringSet();
                    syncType.add(lrObj.getSyncType());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.SYNC_TYPE.name(),
                            syncType);

                    String syncedInst = lrObj.getSynchronizedInstance();
                    if (syncedInst != null) {
                        StringSet synchronizedInstance = new StringSet();
                        synchronizedInstance.add(syncedInst);
                        unManagedVolumeInformation.put(
                                SupportedVolumeInformation.SYNCHRONIZED_INSTANCE
                                        .name(), synchronizedInstance);
                    }
                } else if (LocalReplicaObject.Types.BlockSnapshot.equals(lrObj
                        .getType())) {
                    _logger.info("Found Snapshot {}",
                            unManagedVolume.getNativeGuid());
                    // setting snapshot specific info
                    unManagedVolumeCharacteristics.put(SupportedVolumeCharacterstics.IS_SNAP_SHOT.name(), TRUE);
                    StringSet sourceVolume = new StringSet();
                    sourceVolume.add(lrObj.getSourceNativeGuid());
                    unManagedVolumeInformation
                            .put(SupportedVolumeInformation.LOCAL_REPLICA_SOURCE_VOLUME
                                    .name(), sourceVolume);

                    StringSet isSyncActive = new StringSet();
                    isSyncActive.add(new Boolean(lrObj.isSyncActive())
                            .toString());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.IS_SYNC_ACTIVE.name(),
                            isSyncActive);

                    StringSet needsCopyToTarget = new StringSet();
                    needsCopyToTarget.add(new Boolean(lrObj
                            .isNeedsCopyToTarget()).toString());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.NEEDS_COPY_TO_TARGET
                                    .name(), needsCopyToTarget);

                    StringSet technologyType = new StringSet();
                    technologyType.add(lrObj.getTechnologyType());
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.TECHNOLOGY_TYPE
                                    .name(), technologyType);

                    String settingsInst = lrObj.getSettingsInstance();
                    if (settingsInst != null) {
                        StringSet settingsInstance = new StringSet();
                        settingsInstance.add(settingsInst);
                        unManagedVolumeInformation.put(
                                SupportedVolumeInformation.SETTINGS_INSTANCE
                                        .name(), settingsInstance);
                    }
                }
            }

            // set volume's isSyncActive
            if (!isLocalReplica) {
                StringSet isSyncActive = new StringSet();
                isSyncActive.add(TRUE);
                unManagedVolumeInformation.put(
                        SupportedVolumeInformation.IS_SYNC_ACTIVE.name(), isSyncActive);
            }

            if (null != pool) {
                unManagedVolume.setStoragePoolUri(pool.getId());
                StringSet pools = new StringSet();
                pools.add(pool.getId().toString());
                unManagedVolumeInformation.put(SupportedVolumeInformation.STORAGE_POOL.toString(), pools);
                StringSet driveTypes = pool.getSupportedDriveTypes();
                if (null != driveTypes) {
                    unManagedVolumeInformation.put(SupportedVolumeInformation.DISK_TECHNOLOGY.toString(),
                            driveTypes);
                }
                StringSet matchedVPools = DiscoveryUtils.getMatchedVirtualPoolsForPool(_dbClient, pool
                        .getId(), unManagedVolumeCharacteristics
                        .get(SupportedVolumeCharacterstics.IS_THINLY_PROVISIONED.toString()));
                if (unManagedVolumeInformation.containsKey(SupportedVolumeInformation.SUPPORTED_VPOOL_LIST
                        .toString())) {

                    _logger.debug("Matched Pools :" + Joiner.on("\t").join(matchedVPools));
                    if (null != matchedVPools && matchedVPools.size() == 0) {
                        // replace with empty string set doesn't work, hence
                        // added explicit code to remove all
                        unManagedVolumeInformation.get(
                                SupportedVolumeInformation.SUPPORTED_VPOOL_LIST.toString()).clear();
                    } else {
                        // replace with new StringSet
                        unManagedVolumeInformation.get(
                                SupportedVolumeInformation.SUPPORTED_VPOOL_LIST.toString()).replace(
                                matchedVPools);
                        _logger.info("Replaced Pools :"
                                + Joiner.on("\t").join(
                                        unManagedVolumeInformation
                                                .get(SupportedVolumeInformation.SUPPORTED_VPOOL_LIST
                                                        .toString())));
                    }
                } else {
                    unManagedVolumeInformation.put(
                            SupportedVolumeInformation.SUPPORTED_VPOOL_LIST.toString(), matchedVPools);
                }
            }

            StringSet provCapacity = new StringSet();
            provCapacity.add(String.valueOf(returnProvisionedCapacity(volumeInstance, keyMap)));
            unManagedVolumeInformation.put(SupportedVolumeInformation.PROVISIONED_CAPACITY.toString(),
                    provCapacity);
            injectVolumeInformation(unManagedVolume, volumeInstance, unManagedVolumeInformation);
            injectVolumeCharacterstics(unManagedVolume, volumeInstance, unManagedVolumeCharacteristics);
            unManagedVolume.getUnmanagedExportMasks().clear();
            unManagedVolume.getInitiatorUris().clear();
            unManagedVolume.getInitiatorNetworkIds().clear();
            if (created) {
                _unManagedVolumesInsert.add(unManagedVolume);
            } else {
                _unManagedVolumesUpdate.add(unManagedVolume);
            }

        } catch(Exception e) {
                _logger.error("Exception: ", e);
            }
            return unManagedVolume;
    }

    /**
     * Update the SLO policy Name for VMAX3 volumes.
     * 
     * @param poolSupportedSLONames
     *            - Pool Supported SLO Names
     * @param unManagedVolumeInformation
     *            - UnManaged Volume Information
     * @param unManagedVolumeCharacteristics
     *            - UnManaged Volume characteristics.
     * @param sgSLOName
     *            - Volume supported SLO Name.
     */
    private void updateSLOPolicies(Set<String> poolSupportedSLONames,
            Map<String, StringSet> unManagedVolumeInformation,
            Map<String, String> unManagedVolumeCharacteristics, String sgSLOName) {
        if (null != poolSupportedSLONames && !poolSupportedSLONames.isEmpty()) {
            StringSet sloNamesSet = new StringSet();
            for (String poolSLOName : poolSupportedSLONames) {
                if (null != sgSLOName && poolSLOName.contains(sgSLOName)) {
                    // This condition will filter out the pool SLO's which
                    // contains Workload when sgSLOName contains
                    // just SLO.
                    if (!sgSLOName.contains(Constants.WORKLOAD) && poolSLOName.contains(Constants.WORKLOAD)) {
                        continue;
                    }
                    sloNamesSet.add(poolSLOName);
                    _logger.info("found a matching slo: {}", poolSLOName);
                    break;
                }
            }
            if (!sloNamesSet.isEmpty()) {
                unManagedVolumeInformation.put(SupportedVolumeInformation.AUTO_TIERING_POLICIES.toString(),
                        sloNamesSet);
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_AUTO_TIERING_ENABLED.toString(),
                        Boolean.TRUE.toString());
            } else {
                _logger.warn("StorageGroup SLOName is not found in Pool settings.");
                unManagedVolumeCharacteristics.put(
                        SupportedVolumeCharacterstics.IS_AUTO_TIERING_ENABLED.toString(),
                        Boolean.FALSE.toString());
            }
        }
    }

    /**
     * Only Volumes with Usage 2 can be ingested, other volumes apart from
     * replicas have usage other than 2. Volumes which are set EMCSVIsBound as
     * false cannot be ingested
     * 
     * @param volumeInstance
     * @return
     */
    private boolean isVolumeIngestable(CIMInstance volumeInstance, String isBound, String usageProp) {
        String usage = getCIMPropertyValue(volumeInstance, usageProp);
        if (Boolean.valueOf(isBound)
                && (TWO.equalsIgnoreCase(usage) || NINE.equalsIgnoreCase(usage)
                        || SEVEN.equalsIgnoreCase(usage) || ELEVEN.equalsIgnoreCase(usage)
                        || USAGE_LOCAL_REPLICA_TARGET.equalsIgnoreCase(usage)
                        || USAGE_DELTA_REPLICA_TARGET.equalsIgnoreCase(usage)
                        || USGAE_LOCAL_REPLICA_SOURCE.equalsIgnoreCase(usage)
                        || USAGE_LOCAL_REPLICA_SOURCE_OR_TARGET.equalsIgnoreCase(usage)))
            return true;
        return false;
    }

    @Override
    protected void setPrerequisiteObjects(List<Object> inputArgs) throws BaseCollectionException {
        _args = inputArgs;
    }
}
