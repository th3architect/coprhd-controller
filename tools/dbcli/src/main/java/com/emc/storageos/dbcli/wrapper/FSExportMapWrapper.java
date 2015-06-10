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
package com.emc.storageos.dbcli.wrapper;

import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import com.emc.storageos.db.client.model.FSExportMap;
import com.emc.storageos.dbcli.adapter.FSExportMapAdapter;

@XmlRootElement(name="wrapper")
public class FSExportMapWrapper extends Wrapper<FSExportMap>{

    private FSExportMap fSExportMap = new FSExportMap();
 
    @XmlJavaTypeAdapter(FSExportMapAdapter.class)
    @XmlElement(name="fSExportMap")
    public FSExportMap getValue() {
        return fSExportMap;
    }
 
    public void setValue(FSExportMap fSExportMap) {
        this.fSExportMap = fSExportMap;
    }

}
