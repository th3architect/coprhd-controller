/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.vnxe.models;

import java.util.List;

import org.codehaus.jackson.map.annotate.JsonSerialize;

@JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
public class LunGroupCreateParam extends ParamBase{
    private String description;
    private SnapScheduleParam snapScheduleParameters;
    private FastVPParam fastVPParameters;
    private List<BlockHostAccess> blockHostAccess;
    private List<LunCreateParam> lunCreate;
    private List<LunAddParam> lunAdd;
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }
    public SnapScheduleParam getSnapScheduleParameters() {
        return snapScheduleParameters;
    }
    public void setSnapScheduleParameters(SnapScheduleParam snapScheduleParameters) {
        this.snapScheduleParameters = snapScheduleParameters;
    }
    public FastVPParam getFastVPParameters() {
        return fastVPParameters;
    }
    public void setFastVPParameters(FastVPParam fastVPParameters) {
        this.fastVPParameters = fastVPParameters;
    }
    public List<BlockHostAccess> getBlockHostAccess() {
        return blockHostAccess;
    }
    public void setBlockHostAccess(List<BlockHostAccess> blockHostAccess) {
        this.blockHostAccess = blockHostAccess;
    }
    public List<LunCreateParam> getLunCreate() {
        return lunCreate;
    }
    public void setLunCreate(List<LunCreateParam> lunCreate) {
        this.lunCreate = lunCreate;
    }
    public List<LunAddParam> getLunAdd() {
        return lunAdd;
    }
    public void setLunAdd(List<LunAddParam> lunAdd) {
        this.lunAdd = lunAdd;
    }
    
    
    
}
