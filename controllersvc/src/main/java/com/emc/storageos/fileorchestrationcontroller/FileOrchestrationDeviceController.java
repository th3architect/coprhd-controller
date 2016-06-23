/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.fileorchestrationcontroller;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.FileShare;
import com.emc.storageos.db.client.model.SMBFileShare;
import com.emc.storageos.db.client.model.SMBShareMap;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.filereplicationcontroller.FileReplicationDeviceController;
import com.emc.storageos.model.ResourceOperationTypeEnum;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.ControllerLockingService;
import com.emc.storageos.volumecontroller.FileSMBShare;
import com.emc.storageos.volumecontroller.impl.FileDeviceController;
import com.emc.storageos.volumecontroller.impl.file.CreateMirrorFileSystemsCompleter;
import com.emc.storageos.volumecontroller.impl.file.FileCreateWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.file.FileDeleteWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.file.FileFailoverWorkFlowCompleter;
import com.emc.storageos.volumecontroller.impl.file.FileWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.file.MirrorFileFailoverTaskCompleter;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowService;

public class FileOrchestrationDeviceController implements FileOrchestrationController, Controller {
    private static final Logger s_logger = LoggerFactory.getLogger(FileOrchestrationDeviceController.class);

    private static DbClient s_dbClient;
    private WorkflowService _workflowService;
    private static FileDeviceController _fileDeviceController;
    private static FileReplicationDeviceController _fileReplicationDeviceController;
    private ControllerLockingService _locker;

    static final String CREATE_FILESYSTEMS_WF_NAME = "CREATE_FILESYSTEMS_WORKFLOW";
    static final String DELETE_FILESYSTEMS_WF_NAME = "DELETE_FILESYSTEMS_WORKFLOW";
    static final String EXPAND_FILESYSTEMS_WF_NAME = "EXPAND_FILESYSTEMS_WORKFLOW";
    static final String CHANGE_FILESYSTEMS_VPOOL_WF_NAME = "CHANGE_FILESYSTEMS_VPOOL_WORKFLOW";
    static final String CREATE_MIRROR_FILESYSTEMS_WF_NAME = "CREATE_MIRROR_FILESYSTEMS_WF_NAME";
    static final String FAILOVER_FILESYSTEMS_WF_NAME = "FAILOVER_FILESYSTEM_WORKFLOW";

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController#createFileSystems(java.util.List, java.lang.String)
     */

    /**
     * Creates one or more filesystem
     * (FileShare, FileMirroring). This method is responsible for creating
     * a Workflow and invoking the FileOrchestrationInterface.addStepsForCreateFileSystems
     * 
     * @param fileDescriptors
     * @param taskId
     * @throws ControllerException
     */
    @Override
    public void createFileSystems(List<FileDescriptor> fileDescriptors,
            String taskId) throws ControllerException {

        // Generate the Workflow.
        Workflow workflow = null;
        List<URI> fsUris = FileDescriptor.getFileSystemURIs(fileDescriptors);

        FileCreateWorkflowCompleter completer = new FileCreateWorkflowCompleter(fsUris, taskId, fileDescriptors);
        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    CREATE_FILESYSTEMS_WF_NAME, false, taskId);
            String waitFor = null;    // the wait for key returned by previous call

            s_logger.info("Generating steps for create FileSystem");
            // First, call the FileDeviceController to add its methods.
            waitFor = _fileDeviceController.addStepsForCreateFileSystems(workflow, waitFor,
                    fileDescriptors, taskId);
            // second, call create replication link or pair
            waitFor = _fileReplicationDeviceController.addStepsForCreateFileSystems(workflow, waitFor,
                    fileDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Create filesystems successful for: " + fsUris.toString();
            Object[] callbackArgs = new Object[] { fsUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);

        } catch (Exception ex) {
            s_logger.error("Could not create filesystems: " + fsUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.CREATE_FILE_SYSTEM.getName();
            ServiceError serviceError = DeviceControllerException.errors.createFileSharesFailed(
                    fsUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController#changeFileSystemVirtualPool(java.util.List,
     * java.lang.String)
     */

    /**
     * Create target filesystems for existing file systems!!
     * (FileShare, FileMirroring). This method is responsible for creating
     * a Workflow and invoking the FileOrchestrationInterface.addStepsForCreateFileSystems
     * 
     * @param filesystems
     * @param taskId
     * @throws ControllerException
     */
    @Override
    public void createTargetsForExistingSource(String fs, List<FileDescriptor> fileDescriptors,
            String taskId) throws ControllerException {

        // Generate the Workflow.
        Workflow workflow = null;
        List<URI> fsUris = FileDescriptor.getFileSystemURIs(fileDescriptors);

        CreateMirrorFileSystemsCompleter completer = new CreateMirrorFileSystemsCompleter(fsUris, taskId, fileDescriptors);
        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    CREATE_MIRROR_FILESYSTEMS_WF_NAME, false, taskId);
            String waitFor = null;    // the wait for key returned by previous call

            s_logger.info("Generating steps for creating mirror filesystems...");
            // First, call the FileDeviceController to add its methods.
            // To create target file systems!!
            waitFor = _fileDeviceController.addStepsForCreateFileSystems(workflow, waitFor,
                    fileDescriptors, taskId);
            // second, call create replication link or pair
            waitFor = _fileReplicationDeviceController.addStepsForCreateFileSystems(workflow, waitFor,
                    fileDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Change filesystems vpool successful for: " + fs;
            Object[] callbackArgs = new Object[] { fsUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);

        } catch (Exception ex) {
            s_logger.error("Could not change the filesystem vpool: " + fs, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.CHANGE_FILE_SYSTEM_VPOOL.getName();
            ServiceError serviceError = DeviceControllerException.errors.createFileSharesFailed(
                    fsUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController#deleteFileSystems(java.util.List, java.lang.String)
     */
    /**
     * Deletes one or more filesystem.
     * 
     * @param fileDescriptors
     * @param taskId
     * @throws ControllerException
     */
    @Override
    public void deleteFileSystems(List<FileDescriptor> fileDescriptors,
            String taskId) throws ControllerException {
        String waitFor = null;    // the wait for key returned by previous call
        List<URI> fileShareUris = FileDescriptor.getFileSystemURIs(fileDescriptors);
        FileDeleteWorkflowCompleter completer = new FileDeleteWorkflowCompleter(fileShareUris, taskId);
        Workflow workflow = null;

        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    DELETE_FILESYSTEMS_WF_NAME, false, taskId);

            // Call the FileReplicationDeviceController to add its delete methods if there are Mirror FileShares.
            waitFor = _fileReplicationDeviceController.addStepsForDeleteFileSystems(workflow,
                    waitFor, fileDescriptors, taskId);

            // Next, call the FileDeviceController to add its delete methods.
            waitFor = _fileDeviceController.addStepsForDeleteFileSystems(workflow, waitFor, fileDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Delete FileShares successful for: " + fileShareUris.toString();
            Object[] callbackArgs = new Object[] { fileShareUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);

        } catch (Exception ex) {
            s_logger.error("Could not delete FileShares: " + fileShareUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.DELETE_FILE_SYSTEM.getName();
            ServiceError serviceError = DeviceControllerException.errors.deleteFileSharesFailed(
                    fileShareUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.emc.storageos.fileorchestrationcontroller.FileOrchestrationController#expandFileSystem(java.net.URI, long, java.lang.String)
     */
    /**
     * expand one or more filesystem
     * 
     * @param fileDescriptors
     * @param taskId
     * @throws ControllerException
     */
    @Override
    public void expandFileSystem(List<FileDescriptor> fileDescriptors,
            String taskId) throws ControllerException {
        String waitFor = null;    // the wait for key returned by previous call
        List<URI> fileShareUris = FileDescriptor.getFileSystemURIs(fileDescriptors);
        FileWorkflowCompleter completer = new FileWorkflowCompleter(fileShareUris, taskId);
        Workflow workflow = null;
        try {
            // Generate the Workflow.
            workflow = _workflowService.getNewWorkflow(this,
                    EXPAND_FILESYSTEMS_WF_NAME, false, taskId);
            // Next, call the FileDeviceController to add its delete methods.
            waitFor = _fileDeviceController.addStepsForExpandFileSystems(workflow, waitFor, fileDescriptors, taskId);

            // Finish up and execute the plan.
            // The Workflow will handle the TaskCompleter
            String successMessage = "Expand FileShares successful for: " + fileShareUris.toString();
            Object[] callbackArgs = new Object[] { fileShareUris };
            workflow.executePlan(completer, successMessage, new WorkflowCallback(), callbackArgs, null, null);
        } catch (Exception ex) {
            s_logger.error("Could not Expand FileShares: " + fileShareUris, ex);
            releaseWorkflowLocks(workflow);
            String opName = ResourceOperationTypeEnum.EXPORT_FILE_SYSTEM.getName();
            ServiceError serviceError = DeviceControllerException.errors.expandFileShareFailed(fileShareUris.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    @SuppressWarnings("serial")
    public static class WorkflowCallback implements Workflow.WorkflowCallbackHandler, Serializable {
        @SuppressWarnings("unchecked")
        @Override
        public void workflowComplete(Workflow workflow, Object[] args)
                throws WorkflowException {
            List<URI> filesystems = (List<URI>) args[0];
            // String msg = FileDeviceController.getFileSharesMsg(_dbClient, filesystems);
            // s_logger.info("Processed FileShares:\n" + msg);
        }
    }

    private void releaseWorkflowLocks(Workflow workflow) {
        if (workflow == null) {
            return;
        }
        s_logger.info("Releasing all workflow locks with owner: {}", workflow.getWorkflowURI());
        _workflowService.releaseAllWorkflowLocks(workflow);
    }

    public WorkflowService getWorkflowService() {
        return _workflowService;
    }

    public void setWorkflowService(WorkflowService workflowService) {
        this._workflowService = workflowService;
    }

    public DbClient getDbClient() {
        return s_dbClient;
    }

    public void setDbClient(DbClient dbClient) {
        this.s_dbClient = dbClient;
    }

    public void setLocker(ControllerLockingService locker) {
        this._locker = locker;
    }

    public FileDeviceController getFileDeviceController() {
        return _fileDeviceController;
    }

    public void setFileDeviceController(FileDeviceController fileDeviceController) {
        this._fileDeviceController = fileDeviceController;
    }

    public FileReplicationDeviceController getFileReplicationFileDeviceController() {
        return _fileReplicationDeviceController;
    }

    public void setFileReplicationDeviceController(FileReplicationDeviceController fileReplicationDeviceController) {
        this._fileReplicationDeviceController = fileReplicationDeviceController;
    }

    @Override
    public void fileSystemFailoverWorkflow(URI fsURI, StoragePort storageport, String taskId) throws ControllerException {
        FileFailoverWorkFlowCompleter completer = null;
        Workflow workflow = null;
        try {
            FileShare sourceFileShare = s_dbClient.queryObject(FileShare.class, fsURI);
            List<String> targetfileUris = new ArrayList<String>();
            targetfileUris.addAll(sourceFileShare.getMirrorfsTargets());
            FileShare targetFileShare = s_dbClient.queryObject(FileShare.class, URI.create(targetfileUris.get(0)));
            StorageSystem systemTarget = s_dbClient.queryObject(StorageSystem.class, targetFileShare.getStorageDevice());

            completer = new FileFailoverWorkFlowCompleter(fsURI, taskId);
            workflow = _workflowService.getNewWorkflow(this, FAILOVER_FILESYSTEMS_WF_NAME, false, taskId);

            // Step 1
            String waitForFailover = failoverFileSystem(workflow, systemTarget.getId(), sourceFileShare.getId(), targetFileShare.getId());

            // Step 2
            SMBShareMap smbShareMap = sourceFileShare.getSMBFileShares();
            if (smbShareMap != null) {
                replicateCIFSshareToTarget(workflow, systemTarget.getId(), targetFileShare, smbShareMap, storageport, waitForFailover);
            }
            String successMessage = "Failover filesystems successful for: " + sourceFileShare.getLabel();
            workflow.executePlan(completer, successMessage);

        } catch (Exception ex) {
            s_logger.error("Could not failover filesystems: " + fsURI, ex);
            String opName = ResourceOperationTypeEnum.FILE_PROTECTION_ACTION_FAILOVER.getName();
            ServiceError serviceError = DeviceControllerException.errors.createFileSharesFailed(
                    fsURI.toString(), opName, ex);
            completer.error(s_dbClient, _locker, serviceError);
        }
    }

    String failoverFileSystem(Workflow workflow, URI systemTarget, URI sourceFileShare, URI targetFileShare) {
        s_logger.info("Step 1 : failover FileSystem to target Cluster");

        String failoverStep = workflow.createStepId();
        MirrorFileFailoverTaskCompleter completer = new MirrorFileFailoverTaskCompleter(sourceFileShare, targetFileShare,
                failoverStep);
        String waitForFailover = _fileReplicationDeviceController.addStepsForFailoverFileSystems(workflow, systemTarget, targetFileShare,
                completer, failoverStep);
        return waitForFailover;
    }

    String replicateCIFSshareToTarget(Workflow workflow, URI systemTarget, FileShare targetFileShare,
            SMBShareMap smbShareMap, StoragePort sport, String waitForFailover) {
        String waitForShares = null;
        s_logger.info("Step 2 :Replicate source CIFFS shares to target Cluster");
        String shareCreationStep = workflow.createStepId();

        List<SMBFileShare> smbShares = new ArrayList<SMBFileShare>(smbShareMap.values());

        for (SMBFileShare smbShare : smbShares) {
            FileSMBShare fileSMBShare = new FileSMBShare(smbShare);
            fileSMBShare.setStoragePortName(sport.getPortName());
            fileSMBShare.setStoragePortNetworkId(sport.getPortNetworkId());
            fileSMBShare.setStoragePortGroup(sport.getPortGroup());
            fileSMBShare.setPath(targetFileShare.getPath());
            waitForShares = _fileDeviceController.addStepsForCreatingCIFSShares(workflow, systemTarget, targetFileShare.getId(),
                    fileSMBShare, waitForFailover, shareCreationStep);
        }
        return waitForShares;
    }
}
