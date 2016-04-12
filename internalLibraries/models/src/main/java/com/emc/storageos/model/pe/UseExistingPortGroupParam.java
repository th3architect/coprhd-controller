package com.emc.storageos.model.pe;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class UseExistingPortGroupParam {

    private String portGroupId;

    @XmlElement
    public String getPortGroupId ()
    {
        return portGroupId;
    }

    public void setPortGroupId (String portGroupId)
    {
        this.portGroupId = portGroupId;
    }

    @Override
    public String toString()
    {
        return "ClassPojo [portGroupId = "+portGroupId+"]";
    }
}
