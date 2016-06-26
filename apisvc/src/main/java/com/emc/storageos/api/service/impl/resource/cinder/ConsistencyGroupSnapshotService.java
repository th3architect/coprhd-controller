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
package com.emc.storageos.api.service.impl.resource.cinder;

import static com.emc.storageos.api.mapper.TaskMapper.toTask;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.impl.placement.PlacementManager;
import com.emc.storageos.api.service.impl.resource.BlockService;
import com.emc.storageos.api.service.impl.resource.BlockServiceApi;
import com.emc.storageos.api.service.impl.resource.fullcopy.BlockFullCopyManager;
import com.emc.storageos.api.service.impl.resource.utils.CinderApiUtils;
import com.emc.storageos.cinder.CinderConstants;
import com.emc.storageos.cinder.model.ConsistencyGroupSnapshotCreateRequest;
import com.emc.storageos.cinder.model.ConsistencyGroupSnapshotCreateResponse;
import com.emc.storageos.cinder.model.ConsistencyGroupSnapshotDetail;
import com.emc.storageos.cinder.model.ConsistencyGroupSnapshotListDetail;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockConsistencyGroup.Types;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.Operation;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.ScopedLabel;
import com.emc.storageos.db.client.model.ScopedLabelSet;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.Task;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.model.util.TaskUtils;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authorization.ACL;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.services.OperationTypeEnum;

/**
 * This class provides CRUD operations on consistency group snapshot
 * 
 * @author singhc1
 *
 */
@Path("/v2/{tenant_id}/cgsnapshots")
@DefaultPermissions(readRoles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, readAcls = {
        ACL.OWN, ACL.ALL }, writeRoles = { Role.TENANT_ADMIN }, writeAcls = {
        ACL.OWN, ACL.ALL })
public class ConsistencyGroupSnapshotService extends AbstractConsistencyGroupService {

    private static final Logger _log = LoggerFactory.getLogger(ConsistencyGroupSnapshotService.class);
    
    // Block service implementations
    private Map<String, BlockServiceApi> _blockServiceApis;

    public void setBlockServiceApis(final Map<String, BlockServiceApi> serviceInterfaces) {
        _blockServiceApis = serviceInterfaces;
    }

    // A reference to the placement manager.
    private PlacementManager _placementManager;

    /**
     * Setter for the placement manager.
     * 
     * @param placementManager
     *            A reference to the placement manager.
     */
    public void setPlacementManager(PlacementManager placementManager) {
        _placementManager = placementManager;
    }

    /**
     * Create consistency group snapshot
     * 
     * @param openstackTenantId
     *            openstack tenant Id
     * @param param
     *            Pojo class to bind request
     * @param isV1Call
     *            cinder V1 api
     * @param header
     *            HTTP header
     * @brief Create Consistency Group Snapshot
     * @return Response
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response createConsistencyGroupSnapshot(@PathParam("tenant_id") String openstackTenantId,
            final ConsistencyGroupSnapshotCreateRequest param, @HeaderParam("X-Cinder-V1-Call") String isV1Call, @Context HttpHeaders header) {
        // Query Consistency Group
        final String consistencyGroupId = param.cgsnapshot.consistencygroup_id;
        final BlockConsistencyGroup consistencyGroup = findConsistencyGroup(consistencyGroupId, openstackTenantId);
        
        if (consistencyGroup == null) {
            _log.error("Not Found : No Such Consistency Group Found {}", consistencyGroupId);
            return CinderApiUtils.createErrorResponse(404, "Not Found : No Such Consistency Group Found");
        } else if (!consistencyGroupId.equals(CinderApiUtils.splitString(consistencyGroup.getId().toString(), ":", 3))) {
            _log.error("Bad Request : Invalid Snapshot Id {} : Please enter valid or full Id", consistencyGroupId);
            return CinderApiUtils.createErrorResponse(400, "Bad Request : No such consistency id exist, Please enter valid or full Id");
        }

        if (!isSnapshotCreationpermissible(consistencyGroup)) {
            _log.error("Bad Request : vpool not being configured for the snapshots creation");
            return CinderApiUtils.createErrorResponse(400, "Bad Request : vpool not being configured for the snapshots creation");
        }
        // Ensure that the Consistency Group has been created on all of its defined
        // system types.
        if (!consistencyGroup.created()) {
            CinderApiUtils.createErrorResponse(400, "No such consistency group created");
        }

        Project project = getCinderHelper().getProject(openstackTenantId,
                getUserFromContext());

        URI cgStorageControllerURI = consistencyGroup.getStorageController();

        if (!NullColumnValueGetter.isNullURI(cgStorageControllerURI)) {
            // No snapshots for VPLEX consistency groups.
            StorageSystem cgStorageController = _dbClient.queryObject(
                    StorageSystem.class, cgStorageControllerURI);
            if ((DiscoveredDataObject.Type.vplex.name().equals(cgStorageController
                    .getSystemType())) && (!consistencyGroup.checkForType(Types.LOCAL))) {
                CinderApiUtils.createErrorResponse(400, "can't create snapshot for VPLEX");
            }
        }

        // Get the block service implementation
        BlockServiceApi blockServiceApiImpl = getBlockServiceImpl(consistencyGroup);

        // Get the volumes in the consistency group.
        List<Volume> volumeList = blockServiceApiImpl.getActiveCGVolumes(consistencyGroup);

        _log.info("Active CG volume list : " + volumeList);
        // Generate task id
        String taskId = UUID.randomUUID().toString();

        // Set snapshot type.
        String snapshotType = BlockSnapshot.TechnologyType.NATIVE.toString();
        if (consistencyGroup.checkForType(BlockConsistencyGroup.Types.RP)) {
            snapshotType = BlockSnapshot.TechnologyType.RP.toString();
        } else if ((!volumeList.isEmpty()) && (volumeList.get(0).checkForSRDF())) {
            snapshotType = BlockSnapshot.TechnologyType.SRDF.toString();
        }

        // Determine the snapshot volume for RP.
        Volume snapVolume = null;
        if (consistencyGroup.checkForType(BlockConsistencyGroup.Types.RP)) {
            for (Volume volumeToSnap : volumeList) {
                // Get the RP source volume.
                if (volumeToSnap.getPersonality() != null
                        && volumeToSnap.getPersonality().equals(Volume.PersonalityTypes.SOURCE.toString())) {
                    snapVolume = volumeToSnap;
                    break;
                }
            }
        } else if (!volumeList.isEmpty()) {
            // Any volume.
            snapVolume = volumeList.get(0);
        }

        // Validate the snapshot request.
        String snapshotName = param.cgsnapshot.name;
        blockServiceApiImpl.validateCreateSnapshot(snapVolume, volumeList, snapshotType, snapshotName, getFullCopyManager());

        // Set the create inactive flag.
        Boolean createInactive = Boolean.FALSE;
        Boolean readOnly = Boolean.FALSE;

        // Prepare and create the snapshots for the group.
        List<URI> snapIdList = new ArrayList<URI>();
        List<BlockSnapshot> snapshotList = new ArrayList<BlockSnapshot>();
        TaskList response = new TaskList();
        snapshotList.addAll(blockServiceApiImpl.prepareSnapshots(
                volumeList, snapshotType, snapshotName, snapIdList, taskId));
        for (BlockSnapshot snapshot : snapshotList) {
            response.getTaskList().add(toTask(snapshot, taskId));
        }
        blockServiceApiImpl.createSnapshot(snapVolume, snapIdList, snapshotType, createInactive, readOnly, taskId);

        auditBlockConsistencyGroup(OperationTypeEnum.CREATE_CONSISTENCY_GROUP_SNAPSHOT,
                AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN, param.cgsnapshot.name,
                consistencyGroup.getId().toString());
        ConsistencyGroupSnapshotCreateResponse cgSnapshotCreateRes = new ConsistencyGroupSnapshotCreateResponse();

        for (TaskResourceRep rep : response.getTaskList()) {
            URI snapshotUri = rep.getResource().getId();
            BlockSnapshot snap = _dbClient.queryObject(BlockSnapshot.class,
                    snapshotUri);
            snap.setId(snapshotUri);
            snap.setConsistencyGroup(consistencyGroup.getId());
            snap.setLabel(snapshotName);
            if (snap != null) {
                StringMap extensions = snap.getExtensions();

                if (extensions == null) {
                    extensions = new StringMap();
                }
                extensions.put("status", CinderConstants.ComponentStatus.CREATING.getStatus().toLowerCase());
                extensions.put("taskid", rep.getId().toString());
                snap.setExtensions(extensions);
                ScopedLabelSet tagSet = new ScopedLabelSet();
                snap.setTag(tagSet);
                tagSet.add(new ScopedLabel(project.getTenantOrg().getURI().toString(), CinderApiUtils.splitString(snapshotUri.toString(),
                        ":", 3)));

            }
            _dbClient.updateObject(snap);
            cgSnapshotCreateRes.id = CinderApiUtils.splitString(snapshotUri.toString(), ":", 3);
            cgSnapshotCreateRes.name = param.cgsnapshot.name;
        }

        return CinderApiUtils.getCinderResponse(cgSnapshotCreateRes, header, true,CinderConstants.STATUS_OK);

    }

    /**
     * Get Consistency Group Snapshot info
     * 
     * @param openstackTenantId
     *            openstack tenant Id
     * @param consistencyGroupSnapshotId
     *            Consistency Group Snapshot Id
     * @param isV1Call
     *            Cinder V1 api
     * @param header
     *            HTTP Header
     * @brief 
     *      Get Consistency Group Snapshot info
     * @return Response
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{consistencyGroupSnapshot_id}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public Response getConsistencyGroupSnapshotDetail(@PathParam("tenant_id") String openstackTenantId,
            @PathParam("consistencyGroupSnapshot_id") String consistencyGroupSnapshotId, @HeaderParam("X-Cinder-V1-Call") String isV1Call,
            @Context HttpHeaders header) {
        Project project = getCinderHelper().getProject(openstackTenantId, getUserFromContext());
        if (project == null) {
            String message = "Bad Request: Project with the OpenStack Tenant Id : " + openstackTenantId + " does not exist";
            _log.error(message);
            return CinderApiUtils.createErrorResponse(400, message);
        }
        final BlockSnapshot snapshot = findSnapshot(consistencyGroupSnapshotId, openstackTenantId);
        if (null == snapshot) {
            _log.error("Bad Request : Invalid Snapshot Id {}", consistencyGroupSnapshotId);
            return CinderApiUtils.createErrorResponse(400, "Bad Request: No such snapshot id exist");
        } else if (!consistencyGroupSnapshotId.equals(CinderApiUtils.splitString(snapshot.getId().toString(), ":", 3))) {
            _log.error("Bad Request : Invalid Snapshot Id {} : Please enter valid or full Id", consistencyGroupSnapshotId);
            return CinderApiUtils.createErrorResponse(400, "Bad Request: No such snapshot id exist, Please enter valid or full Id");
        }
        ConsistencyGroupSnapshotDetail cgSnapshotDetail = new ConsistencyGroupSnapshotDetail();
        cgSnapshotDetail.id = consistencyGroupSnapshotId;
        cgSnapshotDetail.name = snapshot.getLabel();
        cgSnapshotDetail.created_at = CinderApiUtils.timeFormat(snapshot.getCreationTime());
        cgSnapshotDetail.consistencygroup_id = CinderApiUtils.splitString(snapshot.getConsistencyGroup().toString(), ":", 3);
        StringMap extensions = snapshot.getExtensions();
        String description = null;

        if (extensions != null) {
            description = extensions.get("display_description");

            _log.debug("Retreiving the tasks for snapshot id {}",
                    snapshot.getId());
            List<Task> taskLst = TaskUtils.findResourceTasks(_dbClient,
                    snapshot.getId());
            _log.debug("Retreived the tasks for snapshot id {}",
                    snapshot.getId());
            String taskInProgressId = null;
            if (snapshot.getExtensions().containsKey("taskid"))
            {
                taskInProgressId = snapshot.getExtensions().get("taskid");
                Task acttask = TaskUtils.findTaskForRequestId(_dbClient,
                        snapshot.getId(), taskInProgressId);

                for (Task tsk : taskLst) {
                    if (tsk.getId().toString().equals(taskInProgressId)) {
                        if (tsk.getStatus().equals("ready"))
                        {
                            cgSnapshotDetail.status = CinderConstants.ComponentStatus.AVAILABLE.getStatus().toLowerCase();
                            snapshot.getExtensions().put("status", CinderConstants.ComponentStatus.AVAILABLE.getStatus().toLowerCase());
                            snapshot.getExtensions().remove("taskid");
                        }
                        else if (tsk.getStatus().equals("pending")) {
                            if (tsk.getDescription().equals(ResourceOperationTypeEnum.CREATE_VOLUME_SNAPSHOT.getDescription()))
                            {
                                cgSnapshotDetail.status = CinderConstants.ComponentStatus.CREATING.getStatus().toLowerCase();
                            } else if (tsk.getDescription().equals(ResourceOperationTypeEnum.DELETE_VOLUME_SNAPSHOT.getDescription()))
                            {
                                cgSnapshotDetail.status = CinderConstants.ComponentStatus.DELETING.getStatus().toLowerCase();
                            }
                        }
                        else if (tsk.getStatus().equals("error"))
                        {
                            cgSnapshotDetail.status = CinderConstants.ComponentStatus.ERROR.getStatus().toLowerCase();
                            snapshot.getExtensions().put("status", CinderConstants.ComponentStatus.ERROR.getStatus().toLowerCase());
                            snapshot.getExtensions().remove("taskid");
                        }
                        _dbClient.updateObject(snapshot);
                        break;
                    }
                }
            }
            else if (snapshot.getExtensions().containsKey("status") &&
                    !snapshot.getExtensions().get("status").toString().toLowerCase().equals("")) {
                cgSnapshotDetail.status = snapshot.getExtensions().get("status").toString().toLowerCase();
            }
            else
            {
                // status is available
                cgSnapshotDetail.status = CinderConstants.ComponentStatus.AVAILABLE.getStatus().toLowerCase();
            }
        }
        cgSnapshotDetail.description = (description == null) ? "" : description;
        return CinderApiUtils.getCinderResponse(cgSnapshotDetail, header, true,CinderConstants.STATUS_OK);

    }

    /**
     * Detail Info for Consistency group snapshot
     * 
     * @param openstackTenantId
     *            Openstack tenant id
     * @param isV1Call
     *            openstack V1 call
     * @param header
     *            Http Headers
     * @brief Get Detail Info for Consistency group snapshots
     * @return Response
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/detail")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public Response getConsistencyGroupSnapshotDetailList(
            @PathParam("tenant_id") String openstackTenantId,
            @HeaderParam("X-Cinder-V1-Call") String isV1Call,
            @Context HttpHeaders header) {
        ConsistencyGroupSnapshotListDetail cgSnapshotDetailListResponse = new ConsistencyGroupSnapshotListDetail();
        URIQueryResultList cgUris = getCinderHelper().getConsistencyGroupsUris(openstackTenantId, getUserFromContext());
        if (null != cgUris) {
            for (URI cgUri : cgUris) {
                URIQueryResultList uris = getCinderHelper().getConsistencyGroupSnapshotUris(cgUri);
                if (null != uris) {
                    for (URI cgSnapshotURI : uris) {
                        BlockSnapshot blockSnapshot = _dbClient.queryObject(BlockSnapshot.class, cgSnapshotURI);
                        if (null != blockSnapshot && !(blockSnapshot.getInactive())) {
                            cgSnapshotDetailListResponse
                                    .addConsistencyGroupSnapshotDetail(getConsistencyGroupSnapshotDetail(blockSnapshot));
                        }
                    }
                }
            }
        }
        return CinderApiUtils.getCinderResponse(cgSnapshotDetailListResponse, header, false,CinderConstants.STATUS_OK);

    }

    /**
     * Delete a consistency group snapshot
     * 
     * @param openstackTenantId
     *            openstack tenant id
     * @param consistencyGroupSnapshot_id
     *            consistency group snapshot id
     * @brief Delete a consistency group snapshot
     * @param isV1Call
     *            Cinder V1 call
     * @param header
     *            Http Header
     * @return Response
     */
    @DELETE
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{consistencyGroupSnapshot_id}")
    @CheckPermission(roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = { ACL.ANY })
    public Response deleteConsistencyGroupSnapshot(
            @PathParam("tenant_id") String openstackTenantId,
            @PathParam("consistencyGroupSnapshot_id") String consistencyGroupSnapshot_id,
            @HeaderParam("X-Cinder-V1-Call") String isV1Call,
            @Context HttpHeaders header) {
        final BlockSnapshot snapshot = findSnapshot(consistencyGroupSnapshot_id, openstackTenantId);
        final URI snapshotCgURI = snapshot.getConsistencyGroup();
        URIQueryResultList uris = getCinderHelper().getConsistencyGroupsUris(openstackTenantId, getUserFromContext());
        boolean isConsistencyGroupHasSnapshotId = false;
        if (uris != null && snapshotCgURI != null) {
            for (URI blockCGUri : uris) {
                BlockConsistencyGroup blockCG = _dbClient.queryObject(
                        BlockConsistencyGroup.class, blockCGUri);
                if (blockCG != null && !blockCG.getInactive()) {
                    if (snapshotCgURI.equals(blockCG.getId())) {
                        isConsistencyGroupHasSnapshotId = true;
                    }
                }
            }
        }
        if (isConsistencyGroupHasSnapshotId) {
            // Generate task id
            final String task = UUID.randomUUID().toString();
            TaskList response = new TaskList();
            // Not an error if the snapshot we try to delete is already deleted
            if (snapshot.getInactive()) {
                Operation op = new Operation();
                op.ready("The consistency group snapshot has already been deactivated");
                op.setResourceType(ResourceOperationTypeEnum.DELETE_CONSISTENCY_GROUP_SNAPSHOT);
                _dbClient.createTaskOpStatus(BlockSnapshot.class, snapshot.getId(), task, op);
                TaskResourceRep taskResponse = toTask(snapshot, task, op);
                if (taskResponse.getState().equals("ready")) {
                    return Response.status(202).build();
                }

            }

            Volume volume = _permissionsHelper.getObjectById(snapshot.getParent(), Volume.class);
            BlockServiceApi blockServiceApiImpl = BlockService.getBlockServiceImpl(volume, _dbClient);

            blockServiceApiImpl.deleteSnapshot(snapshot, Arrays.asList(snapshot), task, VolumeDeleteTypeEnum.FULL.name());

            auditBlockConsistencyGroup(OperationTypeEnum.DELETE_CONSISTENCY_GROUP_SNAPSHOT,
                    AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN, snapshot.getId()
                            .toString(), snapshot.getLabel());

            return Response.status(202).build();
        } else {
            return CinderApiUtils.createErrorResponse(400, "Snapshot not attached to any active consistencygroup");
        }

    }

    // internal function
    private BlockServiceApi getBlockServiceImpl(final BlockConsistencyGroup cg) {
        BlockServiceApi blockServiceApiImpl = null;
        if (cg.checkForType(Types.RP)) {
            blockServiceApiImpl = getBlockServiceImpl(BlockConsistencyGroup.Types.RP.toString().toLowerCase());
        } else if (cg.checkForType(Types.VPLEX)) {
            blockServiceApiImpl = getBlockServiceImpl(BlockConsistencyGroup.Types.VPLEX.toString().toLowerCase());
        } else {
            blockServiceApiImpl = getBlockServiceImpl("group");
        }
        return blockServiceApiImpl;
    }

    private BlockServiceApi getBlockServiceImpl(final String type) {
        return _blockServiceApis.get(type);
    }

    /**
     * Creates and returns an instance of the block full copy manager to handle
     * a full copy request.
     * 
     * @return BlockFullCopyManager
     */
    private BlockFullCopyManager getFullCopyManager() {
        BlockFullCopyManager fcManager = new BlockFullCopyManager(_dbClient,
                _permissionsHelper, _auditMgr, _coordinator, _placementManager, sc, uriInfo,
                _request, null);
        return fcManager;
    }

    /**
     * Record audit log for Block service
     * 
     * @param auditType
     *            Type of AuditLog
     * @param operationalStatus
     *            Status of operation
     * @param operationStage
     *            Stage of operation. For sync operation, it should be null; For async operation, it
     *            should be "BEGIN" or "END";
     * @param descparams
     *            Description paramters
     */
    public void auditBlockConsistencyGroup(final OperationTypeEnum auditType,
            final String operationalStatus, final String operationStage, final Object... descparams) {

        _auditMgr.recordAuditLog(URI.create(getUserFromContext().getTenantId()),
                URI.create(getUserFromContext().getName()), "block", auditType,
                System.currentTimeMillis(), operationalStatus, operationStage, descparams);
    }

    /**
     * Find Snapshot based on snapshot id and tenant id
     * 
     * @param snapshotId
     *            Snapshot id
     * @param openstackTenantId
     *            tenant Id
     * @return BlockSnapshot
     */
    private BlockSnapshot findSnapshot(String snapshotId,
            String openstackTenantId) {
        BlockSnapshot snapshot = (BlockSnapshot) getCinderHelper().queryByTag(
                URI.create(snapshotId), getUserFromContext(),BlockSnapshot.class );
        
        return snapshot;
    }

    // internal function
    private ConsistencyGroupSnapshotDetail getConsistencyGroupSnapshotDetail(
            BlockSnapshot blockSnapshot) {
        ConsistencyGroupSnapshotDetail response = new ConsistencyGroupSnapshotDetail();
        if (null != blockSnapshot) {
            response.id = CinderApiUtils.splitString(blockSnapshot.getId().toString(), ":", 3);
            response.name = blockSnapshot.getLabel();
            response.created_at = CinderApiUtils.timeFormat(blockSnapshot.getCreationTime());
            response.status = blockSnapshot.getExtensions().get("status");
            response.consistencygroup_id = CinderApiUtils.splitString(blockSnapshot.getConsistencyGroup().toString(), ":", 3);
        }
        return response;
    }
}
