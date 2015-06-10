/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 * Copyright (c) 2014. EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.cinder.model;

public class VolumeShowResponse {
	
	/**
	 * {
   		"volume":{
      		"status":"available",
      		"attachments":[
 
      				],
      		"links":[
         		{
            	"href":"http://localhost:8776/v2/0c2eba2c5af04d3f9e9d0d410b371fde/volumes/5aa119a8-d25b-45a7-8d1b-88e127885635",
            	"rel":"self"
         		},
         		{
            	"href":"http://localhost:8776/0c2eba2c5af04d3f9e9d0d410b371fde/volumes/5aa119a8-d25b-45a7-8d1b-88e127885635",
            	"rel":"bookmark"
         		}
      			],
      		"availability_zone":"nova",
      		"os-vol-host-attr:host":"ip-10-168-107-25",
      		"source_volid":null,
      		"snapshot_id":null,
      		"id":"5aa119a8-d25b-45a7-8d1b-88e127885635",
      		"description":"Super volume.",
      		"name":"vol-002",
      		"created_at":"2013-02-25T02:40:21.000000",
      		"volume_type":"None",
      		"os-vol-tenant-attr:tenant_id":"0c2eba2c5af04d3f9e9d0d410b371fde",
      		"size":1,
      		"metadata":{
         		"contents":"not junk"
      			}
   			}
		}
	 */
	public Volume volume;
	public class Volume {
		public String status;
		public Attachment attachments[];
		public Link links[];
		public String availability_zone;
		public String source_volid;
		public String snapshot_id;
		public String id;
		public String description;
		public String name;
		public String created_at;
		public String volume_type;
		public String size;
		public Metadata metadata;
	}
	
	public class Attachment {
		
	}
	
	public class Link {
		public String href;
		public String rel;
	}
	
	public class Metadata {
		public String contents;
	}

}
