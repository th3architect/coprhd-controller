package com.emc.storageos.model.pe;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class UseExistingHostParam {

    private String hostId;

    @XmlElement
    public String getHostId ()
    {
        return hostId;
    }

    public void setHostId (String hostId)
    {
        this.hostId = hostId;
    }

    @Override
    public String toString()
    {
        return "ClassPojo [hostId = "+hostId+"]";
    }
}
