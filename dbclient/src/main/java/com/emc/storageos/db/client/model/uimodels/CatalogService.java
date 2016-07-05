/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.db.client.model.uimodels;

import com.emc.storageos.db.client.model.Cf;
import com.emc.storageos.db.client.model.Name;
import com.emc.storageos.db.client.model.NamedRelationIndex;
import com.emc.storageos.db.client.model.NamedURI;

@Cf("CatalogService")
public class CatalogService extends ModelObjectWithACLs implements Cloneable, SortedIndexDataObject {

    public static final boolean DEFAULT_APPROVAL_REQUIRED = false;
    public static final boolean DEFAULT_EXECUTION_WINDOW_REQUIRED = false;
    public static final boolean DEFAULT_SCHEDULER_AVAILABLE = true;
    
    public static final String TITLE = "title";
    public static final String IMAGE = "image";
    public static final String DESCRIPTION = "description";
    public static final String APPROVAL_REQUIRED = "approvalRequired";
    public static final String EXECUTION_WINDOW_REQUIRED = "executionWindowRequired";
    public static final String DEFAULT_EXECUTION_WINDOW_ID = "defaultExecutionWindowId";
    public static final String BASE_SERVICE = "baseService";
    public static final String MAX_SIZE = "maxSize";
    public static final String CATALOG_CATEGORY_ID = "catalogCategoryId";
    public static final String SORTED_INDEX = "sortedIndex";
    public static final String SCHEDULER_AVAILABLE = "schedulerAvailable";
    
    private String title;

    private String image;

    private String description;

    private Boolean approvalRequired = DEFAULT_APPROVAL_REQUIRED;

    private Boolean executionWindowRequired = DEFAULT_EXECUTION_WINDOW_REQUIRED;

    private NamedURI defaultExecutionWindowId;

    private String baseService;

    private Integer maxSize;

    private NamedURI catalogCategoryId;

    private Integer sortedIndex;

    private Boolean schedulerAvailable = DEFAULT_SCHEDULER_AVAILABLE;
    
    @Name(TITLE)
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        setChanged(TITLE);
    }

    @Name(IMAGE)
    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
        setChanged(IMAGE);
    }

    @Name(DESCRIPTION)
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        setChanged(DESCRIPTION);
    }

    @Name(APPROVAL_REQUIRED)
    public Boolean getApprovalRequired() {
        return approvalRequired;
    }

    public void setApprovalRequired(Boolean approvalRequired) {
        this.approvalRequired = approvalRequired;
        setChanged(APPROVAL_REQUIRED);
    }

    @Name(EXECUTION_WINDOW_REQUIRED)
    public Boolean getExecutionWindowRequired() {
        return executionWindowRequired;
    }

    public void setExecutionWindowRequired(Boolean executionWindowRequired) {
        this.executionWindowRequired = executionWindowRequired;
        setChanged(EXECUTION_WINDOW_REQUIRED);
    }

    @Name(DEFAULT_EXECUTION_WINDOW_ID)
    public NamedURI getDefaultExecutionWindowId() {
        return defaultExecutionWindowId;
    }

    public void setDefaultExecutionWindowId(NamedURI defaultExecutionWindowId) {
        if (defaultExecutionWindowId != null) {
            this.defaultExecutionWindowId = defaultExecutionWindowId;
        } else {
            this.defaultExecutionWindowId = new NamedURI(ExecutionWindow.NEXT, "NEXT");
        }
        setChanged(DEFAULT_EXECUTION_WINDOW_ID);
    }

    @Name(BASE_SERVICE)
    public String getBaseService() {
        return baseService;
    }

    public void setBaseService(String baseService) {
        this.baseService = baseService;
        setChanged(BASE_SERVICE);
    }

    @Name(MAX_SIZE)
    public Integer getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(Integer maxSize) {
        this.maxSize = maxSize;
        setChanged(MAX_SIZE);
    }

    @NamedRelationIndex(cf = "NamedRelationIndex", type = CatalogCategory.class)
    @Name(CATALOG_CATEGORY_ID)
    public NamedURI getCatalogCategoryId() {
        return catalogCategoryId;
    }

    public void setCatalogCategoryId(NamedURI catalogCategoryId) {
        this.catalogCategoryId = catalogCategoryId;
        setChanged(CATALOG_CATEGORY_ID);
    }

    @Name(SORTED_INDEX)
    public Integer getSortedIndex() {
        return sortedIndex;
    }

    public void setSortedIndex(Integer sortedIndex) {
        this.sortedIndex = sortedIndex;
        setChanged(SORTED_INDEX);
    }
    
    @Name(EXECUTION_WINDOW_REQUIRED)
    public Boolean getSchedulerAvailable() {
        return schedulerAvailable;
    }

    public void setSchedulerAvailable(Boolean schedulerAvailable) {
        this.schedulerAvailable = schedulerAvailable;
    }

    @Override
    public String toString() {
        return getLabel();
    }

    public CatalogService copy() {
        CatalogService newService = clone();
        newService.setId(null);
        return newService;
    }

    @Override
    protected CatalogService clone() {
        try {
            return (CatalogService) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new Error(e);
        }
    }

    @Override
    public Object[] auditParameters() {
        return new Object[] { getLabel(), getCatalogCategoryId(), getId() };
    }

    
}
