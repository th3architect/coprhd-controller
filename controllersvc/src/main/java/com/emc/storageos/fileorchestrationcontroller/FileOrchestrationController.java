/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.fileorchestrationcontroller;

import java.net.URI;
import java.util.List;

import com.emc.storageos.Controller;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.FileSMBShare;

public interface FileOrchestrationController extends Controller {
    public final static String FILE_ORCHESTRATION_DEVICE = "file-orchestration";

    /**
     * Creates one or more volumes and executes them.
     * This method is responsible for creating a Workflow and invoking the FileOrchestrationInterface.addStepsForCreateFileSystems
     * 
     * @param fileDescriptors - The complete list of FileDescriptors received from the API layer.
     *            This defines what FileSharess need to be created, and in which pool each Fileshare should be created.
     * @param taskId - The overall taskId for the operation.
     * @throws ControllerException
     */
    public abstract void createFileSystems(List<FileDescriptor> fileDescriptors, String taskId)
            throws ControllerException;

    /**
     * Deletes one or more Filesystems
     * 
     * @param fileDescriptors
     * @param taskId
     * @throws ControllerException
     */
    public abstract void deleteFileSystems(List<FileDescriptor> fileDescriptors, String taskId)
            throws ControllerException;

    /**
     * Expands a single fileshare
     * 
     * @param fileDescriptors
     * @param taskId
     * @throws ControllerException
     */
    public abstract void expandFileSystem(List<FileDescriptor> fileDescriptors, String taskId)
            throws ControllerException;

    /**
     * Failover fileshare
     * 
     * @param fsURI
     * @param FileSMBShare
     * @param taskId
     * @throws ControllerException
     */
    public abstract void fileSystemFailoverWorkflow(URI fsURI, FileSMBShare smbShare, String taskId)
            throws ControllerException;

    /**
     * create mirror copies for existing file system
     * This method is responsible for creating a Workflow and invoking the FileOrchestrationInterface.addStepsForCreateFileSystems
     * 
     * @param fileDescriptors - The complete list of FileDescriptors received from the API layer.
     *            This defines what FileSharess need to be created, and in which pool each Fileshare should be created.
     * @param taskId - The overall taskId for the operation.
     * @throws ControllerException
     */
    public abstract void createTargetsForExistingSource(String sourceFs, List<FileDescriptor> fileDescriptors, String taskId)
            throws ControllerException;

}
