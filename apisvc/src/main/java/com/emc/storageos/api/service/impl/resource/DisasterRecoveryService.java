/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource;

import com.emc.storageos.coordinator.client.model.SiteNetworkState;
import com.emc.storageos.coordinator.client.model.SiteNetworkState.NetworkHealth;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.emc.storageos.coordinator.client.service.impl.DualInetAddress;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.apache.curator.framework.recipes.barriers.DistributedBarrier;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.locks.InterProcessLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.emc.storageos.api.mapper.SiteMapper;
import com.emc.storageos.api.service.impl.resource.utils.InternalSiteServiceClient;
import com.emc.storageos.coordinator.client.model.Constants;
import com.emc.storageos.coordinator.client.model.PropertyInfoExt;
import com.emc.storageos.coordinator.client.model.RepositoryInfo;
import com.emc.storageos.coordinator.client.model.Site;
import com.emc.storageos.coordinator.client.model.SiteError;
import com.emc.storageos.coordinator.client.model.SiteInfo;
import com.emc.storageos.coordinator.client.model.SiteMonitorResult;
import com.emc.storageos.coordinator.client.model.SiteState;
import com.emc.storageos.coordinator.client.model.SoftwareVersion;
import com.emc.storageos.coordinator.client.model.DrOperationStatus.InterState;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.coordinator.client.service.DrUtil;
import com.emc.storageos.coordinator.client.service.impl.LeaderSelectorListenerImpl;
import com.emc.storageos.coordinator.common.Configuration;
import com.emc.storageos.coordinator.common.impl.ZkPath;
import com.emc.storageos.coordinator.exceptions.CoordinatorException;
import com.emc.storageos.coordinator.exceptions.RetryableCoordinatorException;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.uimodels.InitialSetup;
import com.emc.storageos.model.dr.DRNatCheckParam;
import com.emc.storageos.model.dr.DRNatCheckResponse;
import com.emc.storageos.model.dr.FailoverPrecheckResponse;
import com.emc.storageos.model.dr.SiteActive;
import com.emc.storageos.model.dr.SiteAddParam;
import com.emc.storageos.model.dr.SiteConfigParam;
import com.emc.storageos.model.dr.SiteConfigRestRep;
import com.emc.storageos.model.dr.SiteDetailRestRep;
import com.emc.storageos.model.dr.SiteErrorResponse;
import com.emc.storageos.model.dr.SiteIdListParam;
import com.emc.storageos.model.dr.SiteList;
import com.emc.storageos.model.dr.SiteParam;
import com.emc.storageos.model.dr.SiteRemoved;
import com.emc.storageos.model.dr.SiteRestRep;
import com.emc.storageos.model.dr.SiteUpdateParam;
import com.emc.storageos.model.property.PropertyConstants;
import com.emc.storageos.model.property.PropertyInfo;
import com.emc.storageos.security.audit.AuditLogManager;
import com.emc.storageos.security.authentication.InternalApiSignatureKeyGenerator;
import com.emc.storageos.security.authentication.InternalApiSignatureKeyGenerator.SignatureKeyType;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.ExcludeLicenseCheck;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.security.ipsec.IPsecConfig;
import com.emc.storageos.services.OperationTypeEnum;
import com.emc.storageos.services.util.SysUtils;
import com.emc.storageos.svcs.errorhandling.resources.APIException;
import com.emc.storageos.svcs.errorhandling.resources.InternalServerErrorException;
import com.emc.vipr.client.ViPRCoreClient;
import com.emc.vipr.client.ViPRSystemClient;
import com.emc.vipr.model.sys.ClusterInfo;

/**
 * APIs implementation to standby sites lifecycle management such as add-standby, remove-standby, failover, pause
 * resume replication etc.
 */
@Path("/site")
@DefaultPermissions(readRoles = { Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN,
        Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN, Role.SYSTEM_MONITOR },
        writeRoles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN })
public class DisasterRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(DisasterRecoveryService.class);

    private static final String SHORTID_FMT = "site%d";
    private static final int MAX_NUM_OF_STANDBY = 10;
    private static final String EVENT_SERVICE_TYPE = "DisasterRecovery";
    private static final String NTPSERVERS = "network_ntpservers";
    private static final int SITE_NAME_LENGTH_LIMIT = 64;
    private static final int SITE_NUMBER_UPPER_LIMIT = 3;

    private static final int SITE_CONNECT_TEST_TIMEOUT = 10 * 1000;
    private static final int SITE_CONNECTION_TEST_PORT = 443;
    private static final String LOCAL_HOST = "localhost";
    private static final String SYSTEM_ENABLE_FIREWALL = "system_enable_firewall";

    private InternalApiSignatureKeyGenerator apiSignatureGenerator;
    private SiteMapper siteMapper;
    private SysUtils sysUtils;
    private CoordinatorClient coordinator;
    private DbClient dbClient;
    private IPsecConfig ipsecConfig;
    private DrUtil drUtil;

    @Autowired
    private AuditLogManager auditMgr;

    /**
     * Record audit log for DisasterRecoveryService
     *
     * @param auditType
     * @param operationalStatus
     * @param operationStage
     * @param descparams
     */
    protected void auditDisasterRecoveryOps(OperationTypeEnum auditType,
            String operationalStatus,
            String operationStage,
            Object... descparams) {
        auditMgr.recordAuditLog(null, null,
                EVENT_SERVICE_TYPE,
                auditType,
                System.currentTimeMillis(),
                operationalStatus,
                operationStage,
                descparams);
    }

    /**
     * init method, this will be called by Spring framework after create bean successfully
     */
    public void init() {
        siteMapper = new SiteMapper();
        startLeaderSelector();
    }

    /**
     * Attach one fresh install site to this active site as standby
     * Or attach a active site for the local standby site when it's first being added.
     * 
     * @param param site detail information
     * @return site response information
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public SiteRestRep addStandby(SiteAddParam param) {
        log.info("Adding standby site: {}", param.getVip());

        precheckForSiteNumber();
        precheckForGeo();

        List<Site> existingSites = drUtil.listStandbySites();
        // parameter validation and precheck
        validateAddParam(param, existingSites);
        // check the version before using the ViPR client, otherwise there might be compatibility issues.
        precheckStandbyVersion(param);

        ViPRCoreClient viprCoreClient;
        SiteConfigRestRep standbyConfig;
        try {
            viprCoreClient = createViPRCoreClient(param.getVip(), param.getUsername(), param.getPassword());
            standbyConfig = viprCoreClient.site().getStandbyConfig();
        } catch (Exception e) {
            log.error("Unexpected error when retrieving standby config", e);
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Cannot retrieve config from standby site");
        }

        String siteId = standbyConfig.getUuid();
        precheckForStandbyAdd(standbyConfig, viprCoreClient);

        InterProcessLock lock = drUtil.getDROperationLock();

        Site standbySite = null;
        try {
            standbySite = new Site();
            standbySite.setCreationTime((new Date()).getTime());
            standbySite.setName(param.getName());
            standbySite.setVdcShortId(drUtil.getLocalVdcShortId());
            standbySite.setVip(standbyConfig.getVip());
            standbySite.setVip6(standbyConfig.getVip6());
            standbySite.getHostIPv4AddressMap().putAll(new StringMap(standbyConfig.getHostIPv4AddressMap()));
            standbySite.getHostIPv6AddressMap().putAll(new StringMap(standbyConfig.getHostIPv6AddressMap()));
            standbySite.setNodeCount(standbyConfig.getNodeCount());
            standbySite.setUuid(standbyConfig.getUuid());
            String shortId = generateShortId(drUtil.listSites());
            standbySite.setSiteShortId(shortId);
            standbySite.setDescription(param.getDescription());
            standbySite.setState(SiteState.STANDBY_ADDING);
            if (log.isDebugEnabled()) {
                log.debug(standbySite.toString());
            }

            // Do this before tx get started which might write key to zk.
            SecretKey secretKey = apiSignatureGenerator.getSignatureKey(SignatureKeyType.INTERVDC_API);

            coordinator.startTransaction();
            coordinator.addSite(standbyConfig.getUuid());
            log.info("Persist standby site to ZK {}", shortId);
            // coordinator.setTargetInfo(standbySite);
            coordinator.persistServiceConfiguration(standbySite.toConfiguration());
            drUtil.recordDrOperationStatus(standbySite.getUuid(), InterState.ADDING_STANDBY);

            // wake up syssvc to regenerate configurations
            long vdcConfigVersion = DrUtil.newVdcConfigVersion();
            drUtil.updateVdcTargetVersion(coordinator.getSiteId(), SiteInfo.DR_OP_ADD_STANDBY, vdcConfigVersion);
            for (Site site : existingSites) {
                drUtil.updateVdcTargetVersion(site.getUuid(), SiteInfo.DR_OP_ADD_STANDBY, vdcConfigVersion);
            }

            // sync site related info with to be added standby site
            long dataRevision = System.currentTimeMillis();
            List<Site> allStandbySites = new ArrayList<>();
            allStandbySites.add(standbySite);
            allStandbySites.addAll(existingSites);
            SiteConfigParam configParam = prepareSiteConfigParam(allStandbySites, ipsecConfig.getPreSharedKey(), standbyConfig.getUuid(), dataRevision, vdcConfigVersion, secretKey);
            viprCoreClient.site().syncSite(standbyConfig.getUuid(), configParam);

            drUtil.updateVdcTargetVersion(siteId, SiteInfo.DR_OP_CHANGE_DATA_REVISION, vdcConfigVersion, dataRevision);
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.ADD_STANDBY, AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN,
                    standbySite.toBriefString());
            return siteMapper.map(standbySite);
        } catch (Exception e) {
            log.error("Internal error for updating coordinator on standby", e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.ADD_STANDBY, AuditLogManager.AUDITLOG_FAILURE, null,
                    standbySite.toBriefString());
            InternalServerErrorException addStandbyFailedException = APIException.internalServerErrors.addStandbyFailed(e.getMessage());
            throw addStandbyFailedException;
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when adding standby %s", siteId));
            }
        }
    }

    /**
     * Prepare all sites related info for synchronizing them from master to be added or resumed standby site
     *
     * @param standbySites All standby sites
     * @param ipsecKey The cluster ipsec key
     * @param targetStandbyUUID The uuid of the target standby
     * @param targetStandbyDataRevision The data revision of the target standby
     * @return SiteConfigParam all the sites configuration
     */
    private SiteConfigParam prepareSiteConfigParam(List<Site> standbySites, String ipsecKey, String targetStandbyUUID,
                                                   long targetStandbyDataRevision, long vdcConfigVersion, SecretKey secretKey) {
        log.info("Preparing to sync sites info among to be added/resumed standby site...");
        Site active = drUtil.getActiveSite();
        SiteConfigParam configParam = new SiteConfigParam();
        SiteParam activeSite = new SiteParam();
        siteMapper.map(active, activeSite);
        activeSite.setIpsecKey(ipsecKey);
        log.info("    active site info:{}", activeSite.toString());
        configParam.setActiveSite(activeSite);

        List<SiteParam> standbySitesParam = new ArrayList<>();
        for (Site standby : standbySites) {
            SiteParam standbyParam = new SiteParam();
            siteMapper.map(standby, standbyParam);
            standbyParam.setSecretKey(new String(Base64.encodeBase64(secretKey.getEncoded()), Charset.forName("UTF-8")));
            if (standby.getUuid().equals(targetStandbyUUID)) {
                log.info("Set data revision for site {} to {}", standby.getUuid(), targetStandbyDataRevision);
                standbyParam.setDataRevision(targetStandbyDataRevision);
            }
            standbySitesParam.add(standbyParam);
            log.info("    standby site info:{}", standbyParam.toString());
        }
        configParam.setStandbySites(standbySitesParam);
        configParam.setVdcConfigVersion(vdcConfigVersion);

        // Need set stanby's NTP same as primary, so standby time is consistent with primary after reboot
        // It's because time inconsistency between primary and standby will cause db rebuild issue: COP-17965
        PropertyInfoExt targetPropInfo = coordinator.getTargetInfo(PropertyInfoExt.class);
        String ntpServers = targetPropInfo.getProperty(NTPSERVERS);
        log.info("    active site ntp servers: {}", ntpServers);
        configParam.setNtpServers(ntpServers);

        return configParam;
    }

    /**
     * Initialize a to be added target standby
     * The current site will be demoted from active to standby during the process
     *
     * @param configParam
     * @return
     */
    @PUT
    @Path("/{uuid}/initstandby")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    @ExcludeLicenseCheck
    public Response syncSites(SiteConfigParam configParam) {
        log.info("sync sites from active site");

        return initStandby(configParam);
    }

    /**
     * Initialize a to-be added/resumed target standby
     * a) re-set all the latest site related info (persisted in ZK) in the target standby
     * b) vdc properties would be changed accordingly
     * c) the target standby reboot
     * d) re-set zk/db data during the target standby reboot
     * e) the target standby would connect with active and sync all the latest ZK&DB data.
     *
     * Scenarios:
     * a) For adding standby site scenario (External API), the current site will be demoted from active to standby during the process
     * b) For resuming standby site scenario (Internal API), the current site's original data will be cleaned by setting new data revision.
     * It is now only used for resuming long paused (> 5 days) standby site
     * 
     * @param configParam
     * @return
     */
    @PUT
    @Path("/internal/initstandby")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response initStandby(SiteConfigParam configParam) {
        try {
            SiteParam activeSiteParam = configParam.getActiveSite();

            ipsecConfig.setPreSharedKey(activeSiteParam.getIpsecKey());

            log.info("Clean up all obsolete site configurations");
            String activeSiteId = activeSiteParam.getUuid();
            Set<String> standbySiteIds = new HashSet<>();
            for (SiteParam standby : configParam.getStandbySites()) {
                standbySiteIds.add(standby.getUuid());
            }

            for (Site siteToRemove : drUtil.listSites()) {
                String siteId = siteToRemove.getUuid();
                if (activeSiteId.equals(siteId) || standbySiteIds.contains(siteId)) {
                    continue;
                }
                drUtil.removeSite(siteToRemove);
            }

            coordinator.addSite(activeSiteParam.getUuid());
            Site activeSite = new Site();
            siteMapper.map(activeSiteParam, activeSite);
            activeSite.setVdcShortId(drUtil.getLocalVdcShortId());
            coordinator.persistServiceConfiguration(activeSite.toConfiguration());

            Long dataRevision = null;
            // Add other standby sites
            for (SiteParam standby : configParam.getStandbySites()) {
                Site site = new Site();
                siteMapper.map(standby, site);
                site.setVdcShortId(drUtil.getLocalVdcShortId());
                coordinator.persistServiceConfiguration(site.toConfiguration());
                coordinator.addSite(standby.getUuid());
                if (standby.getUuid().equals(coordinator.getSiteId())) {
                    dataRevision = standby.getDataRevision();
                    log.info("Set data revision to {}", dataRevision);
                }
                log.info("Persist standby site {} to ZK", standby.getVip());
            }

            if (dataRevision == null) {
                throw new IllegalStateException("Illegal request on standby site. No data revision in request");
            }

            String ntpServers = configParam.getNtpServers();
            PropertyInfoExt targetPropInfo = coordinator.getTargetInfo(PropertyInfoExt.class);
            if (ntpServers != null && !ntpServers.equals(targetPropInfo.getProperty(NTPSERVERS))) {
                targetPropInfo.addProperty(NTPSERVERS, ntpServers);
                coordinator.setTargetInfo(targetPropInfo);
                log.info("Set ntp servers to {}", ntpServers);
            }

            drUtil.updateVdcTargetVersion(coordinator.getSiteId(), SiteInfo.DR_OP_CHANGE_DATA_REVISION,
                    configParam.getVdcConfigVersion(), dataRevision);
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Internal error for updating coordinator on standby", e);
            throw APIException.internalServerErrors.configStandbyFailed(e.getMessage());
        }
    }

    /**
     * Get all sites including standby and active
     * 
     * @return site list contains all sites with detail information
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    public SiteList getSites() {
        log.info("Begin to list all standby sites of local VDC");
        SiteList standbyList = new SiteList();

        for (Site site : drUtil.listSites()) {
            standbyList.getSites().add(siteMapper.mapWithNetwork(site, drUtil));
        }
        
        SiteInfo siteInfo = coordinator.getTargetInfo(coordinator.getSiteId(), SiteInfo.class);
        standbyList.setConfigVersion(siteInfo.getVdcConfigVersion());
        
        return standbyList;
    }

    /**
     * Check if current site is active site
     * 
     * @return SiteActive true if current site is active else false
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/active")
    public SiteActive checkIsActive() {
        log.info("Begin to check if site Active or Standby");
        SiteActive isActiveSite = new SiteActive();

        try {
            Site localSite = drUtil.getLocalSite();
            isActiveSite.setIsActive(localSite.getState() == SiteState.ACTIVE);
            isActiveSite.setLocalSiteName(localSite.getName());
            isActiveSite.setLocalUuid(localSite.getUuid());
            isActiveSite.setIsMultiSite(drUtil.isMultisite());
            return isActiveSite;
        } catch (Exception e) {
            log.error("Can't get site is Active or Standby");
            throw APIException.badRequests.siteIdNotFound();
        }
    }

    /**
     * Get specified site according site UUID
     * 
     * @param uuid site UUID
     * @return site response with detail information
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    @Path("/{uuid}")
    public SiteRestRep getSite(@PathParam("uuid") String uuid) {
        log.info("Begin to get standby site by uuid {}", uuid);

        try {
            Site site = drUtil.getSiteFromLocalVdc(uuid);
            return siteMapper.mapWithNetwork(site, drUtil);
        } catch (Exception e) {
            log.error("Can't find site with specified site ID {}", uuid);
            throw APIException.badRequests.siteIdNotFound();
        }
    }
    
    /**
     * Get local site
     * 
     * @return site response with detail information
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    @Path("/local")
    public SiteRestRep getSite() {
        log.info("Begin to get local site");

        try {
            Site site = drUtil.getLocalSite();
            return siteMapper.map(site);
        } catch (Exception e) {
            log.error("Can't find local site", e);
            throw APIException.badRequests.siteIdNotFound();
        }
    }

    /**
     * @return result that indicates whether local site is removed
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/islocalsiteremoved")
    public SiteRemoved isLocalSiteRemoved() {
        SiteRemoved response = new SiteRemoved();
        Site localSite = drUtil.getLocalSite();
        if (SiteState.ACTIVE == localSite.getState()) {
            return response;
        }
        for (Site remoteSite : drUtil.listSites()) {
            if (remoteSite.getUuid().equals(localSite.getUuid())) {
                continue;
            }
            try (InternalSiteServiceClient client = new InternalSiteServiceClient(remoteSite, coordinator, apiSignatureGenerator)) {
                SiteList sites = client.getSiteList();
                if (!isActiveSite(remoteSite.getUuid(), sites)) {
                    continue;
                }
                if (isSiteContainedBy(localSite.getUuid(), sites)) {
                    return response;
                } else {
                    log.info("According returned result from current active site {}, local site {} has been removed", remoteSite.getUuid(), localSite.getUuid());
                    response.setIsRemoved(true);
                    return response;
                }
            } catch (Exception e) {
                log.warn("Error happened when fetching site list from site {}", remoteSite.getUuid(), e);
                continue;
            }
        }
        return response;
    }

    private boolean isActiveSite(String siteId, SiteList sites) {
        for (SiteRestRep site : sites.getSites()) {
            if (siteId.equals(site.getUuid()) && SiteState.ACTIVE.toString().equals(site.getState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a standby. After successfully done, it stops data replication to this site
     * 
     * @param uuid standby site uuid
     * @return
     */
    @DELETE
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    @Path("/{uuid}")
    public Response remove(@PathParam("uuid") String uuid) {
        SiteIdListParam param = new SiteIdListParam();
        param.getIds().add(uuid);
        return remove(param);
    }

    /**
     * Remove multiple standby sites. After successfully done, it stops data replication to those sites
     * 
     * @param idList site uuid list to be removed
     * @return
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    @Path("/remove")
    public Response remove(SiteIdListParam idList) {
        List<String> siteIdList = idList.getIds();
        String siteIdStr = StringUtils.join(siteIdList, ",");
        log.info("Begin to remove standby site from local vdc by uuid: {}", siteIdStr);
        List<Site> toBeRemovedSites = new ArrayList<>();
        for (String siteId : siteIdList) {
            Site site;
            try {
                site = drUtil.getSiteFromLocalVdc(siteId);
            } catch (Exception ex) {
                log.error("Can't load site {} from ZK", siteId);
                throw APIException.badRequests.siteIdNotFound();
            }
            if (site.getState().equals(SiteState.ACTIVE)) {
                log.error("Unable to remove this site {}. It is active", siteId);
                throw APIException.badRequests.operationNotAllowedOnActiveSite();
            }
            if (site.getState().isDROperationOngoing() && !site.getState().equals(SiteState.STANDBY_SYNCING)) {
                log.error("Unable to remove this site {} in state {}. " +
                        "DR operation other than STANDBY_SYNCING is ongoing", siteId, site.getState().name());
                throw APIException.internalServerErrors.concurrentDROperationNotAllowed(site.getName(),
                        site.getState().toString());
            }
            toBeRemovedSites.add(site);
        }

        // Build a site names' string for more human-readable Exception error message
        StringBuilder siteNamesSb = new StringBuilder();
        for (Site site : toBeRemovedSites) {
            if (siteNamesSb.length() != 0) {
                siteNamesSb.append(", ");
            }
            siteNamesSb.append(site.getName());
        }
        String SiteNamesStr = siteNamesSb.toString();

        try {
            commonPrecheck(siteIdList);
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            throw APIException.internalServerErrors.removeStandbyPrecheckFailed(SiteNamesStr, e.getMessage());
        }

        InterProcessLock lock = drUtil.getDROperationLock(false);

        List<String> sitesString = new ArrayList<>();
        try {
            log.info("Removing sites");
            coordinator.startTransaction();
            for (Site site : toBeRemovedSites) {
                site.setState(SiteState.STANDBY_REMOVING);
                coordinator.persistServiceConfiguration(site.toConfiguration());
                drUtil.recordDrOperationStatus(site.getUuid(), InterState.REMOVING_STANDBY);
                sitesString.add(site.toBriefString());
            }
            log.info("Notify all sites for reconfig");
            long vdcTargetVersion = DrUtil.newVdcConfigVersion();
            for (Site standbySite : drUtil.listSites()) {
                drUtil.updateVdcTargetVersion(standbySite.getUuid(), SiteInfo.DR_OP_REMOVE_STANDBY, vdcTargetVersion);
            }
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.REMOVE_STANDBY, AuditLogManager.AUDITLOG_SUCCESS,
                    AuditLogManager.AUDITOP_BEGIN, StringUtils.join(sitesString, ','));
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Failed to remove site {}", siteIdStr, e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.REMOVE_STANDBY, AuditLogManager.AUDITLOG_FAILURE,
                    null, StringUtils.join(sitesString, ','));
            throw APIException.internalServerErrors.removeStandbyFailed(SiteNamesStr, e.getMessage());
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when removing standby sites: %s", siteIdStr));
            }
        }
    }

    /**
     * Get standby site configuration
     * 
     * @return SiteConfigRestRep standby site configuration.
     */
    @GET
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    @Path("/localconfig")
    public SiteConfigRestRep getStandbyConfig() {
        log.info("Begin to get standby config");
        String siteId = coordinator.getSiteId();
        SecretKey key = apiSignatureGenerator.getSignatureKey(SignatureKeyType.INTERVDC_API);

        Site site = drUtil.getSiteFromLocalVdc(siteId);
        SiteConfigRestRep siteConfigRestRep = new SiteConfigRestRep();
        siteConfigRestRep.setUuid(siteId);
        siteConfigRestRep.setVip(site.getVip());
        siteConfigRestRep.setVip6(site.getVip6());
        siteConfigRestRep.setSecretKey(new String(Base64.encodeBase64(key.getEncoded()), Charset.forName("UTF-8")));
        siteConfigRestRep.setHostIPv4AddressMap(site.getHostIPv4AddressMap());
        siteConfigRestRep.setHostIPv6AddressMap(site.getHostIPv6AddressMap());
        siteConfigRestRep.setDbSchemaVersion(coordinator.getCurrentDbSchemaVersion());
        siteConfigRestRep.setFreshInstallation(isFreshInstallation());
        siteConfigRestRep.setClusterStable(isClusterStable());
        siteConfigRestRep.setNodeCount(site.getNodeCount());
        siteConfigRestRep.setState(site.getState().toString());

        try {
            siteConfigRestRep.setSoftwareVersion(coordinator.getTargetInfo(RepositoryInfo.class)
                    .getCurrentVersion().toString());
        } catch (Exception e) {
            log.error("Fail to get software version {}", e);
        }

        log.info("Return result: {}", siteConfigRestRep);
        return siteConfigRestRep;
    }

    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    @Path("/natcheck")
    @ExcludeLicenseCheck
    public DRNatCheckResponse checkIfBehindNat(DRNatCheckParam checkParam, @HeaderParam("X-Forwarded-For") String clientIp) {
        if (checkParam == null) {
            log.error("checkParam is null, X-Forwarded-For is {}", clientIp);
            throw APIException.internalServerErrors.invalidNatCheckCall("(null)", clientIp);
        }

        String ipv4Str = checkParam.getIPv4Address();
        String ipv6Str = checkParam.getIPv6Address();
        log.info(String.format("Performing NAT check, client address connecting to VIP: %s. Client reports its IPv4 = %s, IPv6 = %s",
                clientIp, ipv4Str, ipv6Str));

        boolean isBehindNat = false;
        try {
            isBehindNat = sysUtils.checkIfBehindNat(ipv4Str, ipv6Str, clientIp);
        } catch (Exception e) {
            log.error("Fail to check NAT {}", e);
            throw APIException.internalServerErrors.invalidNatCheckCall(e.getMessage(), clientIp);
        }

        DRNatCheckResponse resp = new DRNatCheckResponse();
        resp.setSeenIp(clientIp);
        resp.setBehindNAT(isBehindNat);

        return resp;
    }

    /**
     * Pause a standby site that is already sync'ed with the active
     * 
     * @param uuid site UUID
     * @return updated standby site representation
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN, Role.SYSTEM_ADMIN,
            Role.RESTRICTED_SYSTEM_ADMIN}, blockProxies = true)
    @Path("/{uuid}/pause")
    public Response pauseStandby(@PathParam("uuid") String uuid) {
        SiteIdListParam param = new SiteIdListParam();
        param.getIds().add(uuid);
        return pause(param);
    }

    /**
     * Pause data replication to multiple standby sites.
     *
     * @param idList site uuid list to be removed
     * @return
     */
    @POST
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN, Role.SYSTEM_ADMIN,
            Role.RESTRICTED_SYSTEM_ADMIN }, blockProxies = true)
    @Path("/pause")
    public Response pause(SiteIdListParam idList) {
        List<String> siteIdList = idList.getIds();
        String siteIdStr = StringUtils.join(siteIdList, ",");
        log.info("Begin to pause standby site from local vdc by uuid: {}", siteIdStr);
        List<Site> toBePausedSites = new ArrayList<>();
        List<String> siteNameList = new ArrayList<>();
        for (String siteId : siteIdList) {
            Site site;
            try {
                site = drUtil.getSiteFromLocalVdc(siteId);
            } catch (Exception ex) {
                log.error("Can't load site {} from ZK", siteId);
                throw APIException.badRequests.siteIdNotFound();
            }
            SiteState state = site.getState();
            if (state.equals(SiteState.ACTIVE)) {
                log.error("Unable to pause this site {}. It is active", siteId);
                throw APIException.badRequests.operationNotAllowedOnActiveSite();
            }
            if (!state.equals(SiteState.STANDBY_SYNCED)) {
                log.error("Unable to pause this site {}. It is in state {}", siteId, state);
                throw APIException.badRequests.operationOnlyAllowedOnSyncedSite(site.getName(), state.toString());
            }
            toBePausedSites.add(site);
            siteNameList.add(site.getName());
        }

        // This String is only used to output human readable message to user when Exception is thrown
        String siteNameStr = StringUtils.join(siteNameList, ',');
        precheckForPause(siteNameStr);

        try {
            // the site(s) to be paused must be checked as well
            commonPrecheck();
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            throw APIException.internalServerErrors.pauseStandbyPrecheckFailed(siteNameStr, e.getMessage());
        }

        InterProcessLock lock = drUtil.getDROperationLock();

        // any error is not retry-able beyond this point.
        List<String> sitesString = new ArrayList<>();
        try {
            log.info("Pausing sites");
            long vdcTargetVersion = DrUtil.newVdcConfigVersion();
            coordinator.startTransaction();
            for (Site site : toBePausedSites) {
                site.setState(SiteState.STANDBY_PAUSING);
                site.setLastStateUpdateTime(System.currentTimeMillis());
                coordinator.persistServiceConfiguration(site.toConfiguration());
                drUtil.recordDrOperationStatus(site.getUuid(), InterState.PAUSING_STANDBY);
                sitesString.add(site.toBriefString());
                // notify the to-be-paused sites before others.
                drUtil.updateVdcTargetVersion(site.getUuid(), SiteInfo.DR_OP_PAUSE_STANDBY, vdcTargetVersion);
            }
            log.info("Notify all sites for reconfig");
            for (Site site : drUtil.listSites()) {
                if (toBePausedSites.contains(site)) { // Site#equals only compares the site uuid
                    // already notified
                    continue;
                }
                drUtil.updateVdcTargetVersion(site.getUuid(), SiteInfo.DR_OP_PAUSE_STANDBY, vdcTargetVersion);
            }
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.PAUSE_STANDBY, AuditLogManager.AUDITLOG_SUCCESS,
                    AuditLogManager.AUDITOP_BEGIN, StringUtils.join(sitesString, ','));
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Failed to pause site {}", siteIdStr, e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.PAUSE_STANDBY, AuditLogManager.AUDITLOG_FAILURE,
                    null, StringUtils.join(sitesString, ','));
            throw APIException.internalServerErrors.pauseStandbyFailed(siteNameStr, e.getMessage());
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when pausing standby site: %s", siteIdStr));
            }
        }
    }

    private void precheckForPause(String siteNames) {
        PropertyInfo targetProperty = coordinator.getPropertyInfo();
        String firewallEnabled = targetProperty.getProperty(SYSTEM_ENABLE_FIREWALL);
        if (firewallEnabled != null && firewallEnabled.equals("no")) {
            throw APIException.internalServerErrors.pauseStandbyPrecheckFailed(siteNames, "firewall has been disabled." +
                    "Please make sure to keep it enabled until every standby site has been resumed");
        }

        String ipsecEnabled = ipsecConfig.getIpsecStatus();
        if (ipsecEnabled != null && !ipsecEnabled.equals("enabled")) {
            throw APIException.internalServerErrors.pauseStandbyPrecheckFailed(siteNames, "ipsec has been disabled." +
                    "Please make sure to keep it enabled until every standby site has been resumed");
        }
    }

    /**
     * Resume data replication for a paused standby site
     * 
     * @param uuid site UUID
     * @return updated standby site representation
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN, Role.SYSTEM_ADMIN,
            Role.RESTRICTED_SYSTEM_ADMIN }, blockProxies = true)
    @Path("/{uuid}/resume")
    public SiteRestRep resumeStandby(@PathParam("uuid") String uuid) {
        log.info("Begin to resume data sync to standby site identified by uuid: {}", uuid);
        Site standby = validateSiteConfig(uuid);
        SiteState state = standby.getState();
        if (!state.equals(SiteState.STANDBY_PAUSED) && !state.equals(SiteState.ACTIVE_DEGRADED)) {
            log.error("site {} is in state {}, should be STANDBY_PAUSED or ACTIVE_DEGRADED", uuid, standby.getState());
            throw APIException.badRequests.operationOnlyAllowedOnPausedSite(standby.getName(), standby.getState().toString());
        }
        SiteNetworkState networkState = drUtil.getSiteNetworkState(uuid);
        if (networkState.getNetworkHealth() == NetworkHealth.BROKEN) {
            throw APIException.internalServerErrors.siteConnectionBroken(standby.getName(), "Network health state is broken.");
        }

        try (InternalSiteServiceClient client = createInternalSiteServiceClient(standby)) {
            commonPrecheck();

            client.setCoordinatorClient(coordinator);
            client.setKeyGenerator(apiSignatureGenerator);
            client.resumePrecheck();
        } catch (APIException e) {
            throw e;
        } catch (Exception e) {
            throw APIException.internalServerErrors.resumeStandbyPrecheckFailed(standby.getName(), e.getMessage());
        }

        // Do this before tx get started which might write key to zk.
        SecretKey secretKey = apiSignatureGenerator.getSignatureKey(SignatureKeyType.INTERVDC_API);

        InterProcessLock lock = drUtil.getDROperationLock();

        long vdcTargetVersion = DrUtil.newVdcConfigVersion();
        try {
            coordinator.startTransaction();
            for (Site site : drUtil.listStandbySites()) {
                if (site.getUuid().equals(uuid)) {
                    log.error("Re-init the target standby", uuid);

                    // init the to-be resumed standby site
                    long dataRevision = System.currentTimeMillis();
                    List<Site> standbySites = drUtil.listStandbySites();
                    SiteConfigParam configParam = prepareSiteConfigParam(standbySites, ipsecConfig.getPreSharedKey(),
                            uuid, dataRevision, vdcTargetVersion, secretKey);
                    try (InternalSiteServiceClient internalSiteServiceClient = new InternalSiteServiceClient()) {
                        internalSiteServiceClient.setCoordinatorClient(coordinator);
                        internalSiteServiceClient.setServer(site.getVipEndPoint());
                        internalSiteServiceClient.initStandby(configParam);
                    }

                    site.setState(SiteState.STANDBY_RESUMING);
                    coordinator.persistServiceConfiguration(site.toConfiguration());
                    drUtil.recordDrOperationStatus(site.getUuid(), InterState.RESUMING_STANDBY);
                    drUtil.updateVdcTargetVersion(uuid, SiteInfo.DR_OP_CHANGE_DATA_REVISION, vdcTargetVersion, dataRevision);
                } else {
                    drUtil.updateVdcTargetVersion(site.getUuid(), SiteInfo.DR_OP_RESUME_STANDBY, vdcTargetVersion);
                }
            }

            // update the local(active) site last
            drUtil.updateVdcTargetVersion(coordinator.getSiteId(), SiteInfo.DR_OP_RESUME_STANDBY, vdcTargetVersion);
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.RESUME_STANDBY, AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN,
                    standby.toBriefString());

            return siteMapper.map(standby);
        } catch (Exception e) {
            log.error("Error resuming site {}", uuid, e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.RESUME_STANDBY, AuditLogManager.AUDITLOG_FAILURE, null, standby.toBriefString());
            InternalServerErrorException resumeStandbyFailedException =
                    APIException.internalServerErrors.resumeStandbyFailed(standby.getName(), e.getMessage());
            throw resumeStandbyFailedException;
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when resuming standby site: %s", uuid));
            }
        }
    }

    /**
     * This is internal API to do precheck for resume
     */
    @POST
    @Path("/internal/resumeprecheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public SiteErrorResponse resumePrecheck() {
        log.info("Precheck for resume internally");

        SiteErrorResponse response = new SiteErrorResponse();
        try {
            precheckForResumeLocalStandby();
        } catch (APIException e) {
            log.warn("Failed to precheck switchover", e);
            response.setErrorMessage(e.getMessage());
            response.setServiceCode(e.getServiceCode().ordinal());
            return response;
        } catch (Exception e) {
            log.error("Failed to precheck switchover", e);
            response.setErrorMessage(e.getMessage());
            return response;
        }

        return response;
    }


    public void precheckForSiteNumber() {
        int upperLimit = drUtil.getDrIntConfig(DrUtil.KEY_MAX_NUMBER_OF_DR_SITES, SITE_NUMBER_UPPER_LIMIT);
        int siteNum = drUtil.listSites().size();
        if (siteNum >= upperLimit) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(
                    String.format("The maximum number of DR sites(%d) has been reached. Currently %d sites are configured", upperLimit, siteNum));
        }
    }

    private void precheckForResumeLocalStandby() {
        Site localSite = drUtil.getLocalSite();
        if (!isClusterStable()) {
            throw APIException.serviceUnavailable.siteClusterStateNotStable(localSite.getName(),
                    Objects.toString(coordinator.getControlNodesState()));
        }

        if (SiteState.STANDBY_PAUSED != localSite.getState() && SiteState.ACTIVE_DEGRADED != localSite.getState()) {
            throw APIException.internalServerErrors.resumeStandbyPrecheckFailed(localSite.getName(),
                    "Standby site is not in paused state");
        }
    }

    /**
     * Query the latest error message for specific standby site
     * 
     * @param uuid site UUID
     * @return updated standby site representation
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN })
    @Path("/{uuid}/retry")
    public SiteRestRep retryOperation(@PathParam("uuid") String uuid) {
        log.info("Begin to get site error by uuid {}", uuid);
        Site standby;
        try {
            standby = drUtil.getSiteFromLocalVdc(uuid);
        } catch (CoordinatorException e) {
            log.error("Can't find site {} from ZK", uuid);
            throw APIException.badRequests.siteIdNotFound();
        }

        if (!standby.getState().equals(SiteState.STANDBY_ERROR)) {
            log.error("site {} is in state {}, should be STANDBY_ERROR", uuid, standby.getState());
            throw APIException.badRequests.operationOnlyAllowedOnErrorSite(standby.getName(), standby.getState().toString());
        }
        if (!standby.getLastState().equals(SiteState.STANDBY_PAUSING)
                && !standby.getLastState().equals(SiteState.STANDBY_RESUMING)
                && !standby.getLastState().equals(SiteState.STANDBY_FAILING_OVER)) {
            log.error("site {} lastState was {}, retry is only supported for Pause, Resume and Failover", uuid, standby.getLastState());
            throw APIException.badRequests.operationRetryOnlyAllowedOnLastState(standby.getName(), standby.getLastState().toString());
        }

        //Reuse the current action required
        Site localSite = drUtil.getLocalSite();
        SiteInfo siteInfo = coordinator.getTargetInfo(localSite.getUuid(),SiteInfo.class);
        String drOperation = siteInfo.getActionRequired();

        // Check that last action matches retry action
        if (!drOperation.equals(standby.getLastState().getDRAction())) {
            log.error("Active site last operation was {}, retry is only supported if no other operations have been performed", drOperation);
            throw APIException.internalServerErrors.retryStandbyPrecheckFailed(standby.getName(), standby.getLastState().toString(),
                    String.format("Another DR operation %s has been run on Active site. Only the latest operation can be retried. " +
                            "This is an unrecoverable Error, please remove site and deploy a new one.",drOperation));
        }
        
        InterProcessLock lock = drUtil.getDROperationLock();
        try {

            coordinator.startTransaction();
            standby.setState(standby.getLastState());

            //Failover requires setting old active site to last state as well.
            if (standby.getState() == SiteState.STANDBY_FAILING_OVER) {
               for (Site site: drUtil.listSites()){
                   if (site.getLastState() == SiteState.ACTIVE_FAILING_OVER){
                       site.setState(SiteState.ACTIVE_FAILING_OVER);
                       coordinator.persistServiceConfiguration(site.toConfiguration());
                   }
               }
            }

            coordinator.persistServiceConfiguration(standby.toConfiguration());
            log.info("Notify all sites for reconfig");
            long vdcTargetVersion = DrUtil.newVdcConfigVersion();

            for (Site site : drUtil.listSites()) {
                String siteUuid = site.getUuid();
                if (site.getLastState() == SiteState.STANDBY_RESUMING) {
                    SiteInfo siteTargetInfo = coordinator.getTargetInfo(siteUuid, SiteInfo.class);
                    String resumeSiteOperation = siteTargetInfo.getActionRequired();
                    if (resumeSiteOperation.equals(SiteInfo.DR_OP_CHANGE_DATA_REVISION)) {
                        long dataRevision = System.currentTimeMillis();
                        drUtil.updateVdcTargetVersion(siteUuid, resumeSiteOperation, vdcTargetVersion, dataRevision);
                        continue;
                    }
                }
                log.info("Set dr operation {} on site {}", drOperation, siteUuid);
                drUtil.updateVdcTargetVersion(siteUuid, drOperation, vdcTargetVersion);
            }

            coordinator.commitTransaction();
            return siteMapper.map(standby);
        } catch (Exception e) {
            log.error("Error retrying site operation for site {}", uuid, e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.RETRY_STANDBY_OP, AuditLogManager.AUDITLOG_FAILURE, null, standby);
            InternalServerErrorException retryStandbyOpFailedException =
                    APIException.internalServerErrors.retryStandbyOpFailed(standby.getName(), e.getMessage());
            throw retryStandbyOpFailedException;
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when retrying standby site last op: %s", uuid));
            }
        }
    }

    /**
     * Retry last operation when in STANDBY_ERROR
     *
     * @param uuid site UUID
     * @return site response with detail information
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    @Path("/{uuid}/error")
    public SiteErrorResponse getSiteError(@PathParam("uuid") String uuid) {
        log.info("Begin to get site error by uuid {}", uuid);

        try {
            Site standby = drUtil.getSiteFromLocalVdc(uuid);

            if (standby.getState().equals(SiteState.STANDBY_ERROR)) {
                return coordinator.getTargetInfo(uuid, SiteError.class).toResponse();
            }
        } catch (CoordinatorException e) {
            log.error("Can't find site {} from ZK", uuid);
            throw APIException.badRequests.siteIdNotFound();
        } catch (Exception e) {
            log.error("Find find site from ZK for UUID {} : {}" + uuid, e);
        }

        return SiteErrorResponse.noError();
    }

    /**
     * This API will do switchover to target new active site according passed in site UUID. After failover, old active site will
     * work as normal standby site and target site will be promoted to active. All site will update properties to trigger reconfig.
     * 
     * @param uuid target new active site UUID
     * @return return accepted response if operation is successful
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{uuid}/switchover")
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public Response doSwitchover(@PathParam("uuid") String uuid) {
        log.info("Begin to switchover for standby UUID {}", uuid);

        precheckForSwitchoverForActiveSite(uuid);
        
        List<Site> allStandbySites = drUtil.listStandbySites();

        for (Site site : allStandbySites) {
            if (!site.getUuid().equals(uuid) && site.getState() == SiteState.STANDBY_PAUSED) {
                try (InternalSiteServiceClient client = new InternalSiteServiceClient(site)) {
                    client.setCoordinatorClient(coordinator);
                    client.setKeyGenerator(apiSignatureGenerator);
                    client.switchoverPrecheck();
                }
            }
        }

        String oldActiveUUID = drUtil.getActiveSite().getUuid();

        InterProcessLock lock = drUtil.getDROperationLock();

        Site newActiveSite = null;
        Site oldActiveSite = null;
        try {
            newActiveSite = drUtil.getSiteFromLocalVdc(uuid);

            // Set old active site's state, short id and key
            oldActiveSite = drUtil.getSiteFromLocalVdc(oldActiveUUID);
            if (StringUtils.isEmpty(oldActiveSite.getSiteShortId())) {
                oldActiveSite.setSiteShortId(newActiveSite.getVdcShortId());
            }
            coordinator.startTransaction();
            oldActiveSite.setState(SiteState.ACTIVE_SWITCHING_OVER);
            coordinator.persistServiceConfiguration(oldActiveSite.toConfiguration());
            
            // this barrier is set when begin switchover and will be removed by new active site. Old active site will wait and reboot after
            // barrier is removed 
            DistributedBarrier restartBarrier = coordinator.getDistributedBarrier(String.format("%s/%s/%s", ZkPath.SITES,
                    oldActiveSite.getUuid(), Constants.SWITCHOVER_BARRIER_RESTART));
            restartBarrier.setBarrier();
       
            drUtil.recordDrOperationStatus(oldActiveSite.getUuid(), InterState.SWITCHINGOVER_ACTIVE);

            // trigger reconfig
            long vdcConfigVersion = System.currentTimeMillis(); // a version for all sites.
            for (Site eachSite : drUtil.listSites()) {
                if (!eachSite.getUuid().equals(uuid) && eachSite.getState() == SiteState.STANDBY_PAUSED) {
                    try (InternalSiteServiceClient client = new InternalSiteServiceClient(eachSite)) {
                        client.setCoordinatorClient(coordinator);
                        client.setKeyGenerator(apiSignatureGenerator);
                        client.switchover(newActiveSite.getUuid(), vdcConfigVersion);
                    }
                }else {
                    drUtil.updateVdcTargetVersion(eachSite.getUuid(), SiteInfo.DR_OP_SWITCHOVER, vdcConfigVersion, oldActiveSite.getUuid(),
                        newActiveSite.getUuid());
                }
            }
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.SWITCHOVER, AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN,
                    oldActiveSite.toBriefString(), newActiveSite.toBriefString());
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error(String.format("Error happened when switchover from site %s to site %s", oldActiveUUID, uuid), e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.SWITCHOVER, AuditLogManager.AUDITLOG_FAILURE, null,
                    newActiveSite.getName(), newActiveSite.getVipEndPoint());
            throw APIException.internalServerErrors.switchoverFailed(oldActiveSite.getName(), newActiveSite.getName(), e.getMessage());
        } finally {
            try {
                lock.release();
            } catch (Exception ignore) {
                log.error(String.format("Lock release failed when switchover from %s to %s", oldActiveUUID, uuid));
            }
        }
    }
    
    /**
     * This is internal API to do precheck for switchover
     * 
     * @return return response with error message and service code
     */
    @POST
    @Path("/internal/switchoverprecheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public SiteErrorResponse switchoverPrecheck() {
        log.info("Precheck for switchover internally");

        SiteErrorResponse response = new SiteErrorResponse();
        try {
            precheckForSwitchoverForLocalStandby();
        } catch (InternalServerErrorException e) {
            log.warn("Failed to precheck switchover", e);
            response.setErrorMessage(e.getMessage());
            response.setServiceCode(e.getServiceCode().ordinal());
            return response;
        } catch (Exception e) {
            log.error("Failed to precheck switchover", e);
            response.setErrorMessage(e.getMessage());
            return response;
        }

        return response;
    }
    
    /**
     * This is internal API to do switchover
     * 
     * @return return response with error message and service code
     */
    @POST
    @Path("/internal/switchover")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response switchover(@QueryParam("newActiveSiteUUid") String newActiveSiteUUID, @QueryParam("vdcVersion") String vdcTargetVersion) {
        log.info("Begin to switchover internally for standby UUID {}", newActiveSiteUUID);

        Site newActiveSite = null;
        Site oldActiveSite = null;
        try {
            newActiveSite = drUtil.getSiteFromLocalVdc(newActiveSiteUUID);
            oldActiveSite = drUtil.getSiteFromLocalVdc(drUtil.getActiveSite().getUuid());
            if (StringUtils.isEmpty(oldActiveSite.getSiteShortId())) {
                oldActiveSite.setSiteShortId(newActiveSite.getVdcShortId());
            }
            
            oldActiveSite.setState(SiteState.STANDBY_SYNCED);
            coordinator.persistServiceConfiguration(oldActiveSite.toConfiguration());
            
            newActiveSite.setState(SiteState.ACTIVE);
            coordinator.persistServiceConfiguration(newActiveSite.toConfiguration());
            
            drUtil.updateVdcTargetVersion(drUtil.getLocalSite().getUuid(), SiteInfo.DR_OP_SWITCHOVER, Long.parseLong(vdcTargetVersion), oldActiveSite.getUuid(),
                        newActiveSite.getUuid());
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error(String.format("Error happened when switchover to site %s", newActiveSiteUUID), e);
            throw APIException.internalServerErrors.switchoverFailed(oldActiveSite.getName(), newActiveSite.getName(), e.getMessage());
        }        
    }

    /**
     * This API will do failover from standby site. This operation is only allowed when active site is down.
     * After failover, this standby site will be promoted to active site.
     * 
     * @param uuid target new active site UUID
     * @return return accepted response if operation is successful
     */
    @POST
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/{uuid}/failover")
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public Response doFailover(@PathParam("uuid") String uuid) {
        log.info("Begin to failover for standby UUID {}", uuid);

        Site currentSite = drUtil.getSiteFromLocalVdc(uuid);
        precheckForFailoverLocally(uuid);

        List<Site> allStandbySites = drUtil.listStandbySites();

        try {
            coordinator.startTransaction();
            // set state
            String activeSiteId = drUtil.getActiveSite().getUuid();
            Site oldActiveSite = new Site();
            if (StringUtils.isEmpty(activeSiteId)) {
                log.info("Cant't find active site id, go on to do failover");
            } else {
                oldActiveSite = drUtil.getSiteFromLocalVdc(activeSiteId);
                oldActiveSite.setState(SiteState.ACTIVE_FAILING_OVER);
                coordinator.persistServiceConfiguration(oldActiveSite.toConfiguration());
            }

            currentSite.setState(SiteState.STANDBY_FAILING_OVER);
            coordinator.persistServiceConfiguration(currentSite.toConfiguration());
            drUtil.recordDrOperationStatus(currentSite.getUuid(), InterState.FAILINGOVER_STANDBY);

            long vdcTargetVersion = DrUtil.newVdcConfigVersion();
            //reconfig other standby sites
            for (Site site : allStandbySites) {
                if (!site.getUuid().equals(uuid)) {
                    if (site.getState() == SiteState.STANDBY_SYNCED) {
                        site.setState(SiteState.STANDBY_PAUSED);
                        coordinator.persistServiceConfiguration(site.toConfiguration());
                    } else  if (site.getState() == SiteState.STANDBY_REMOVING) {
                        site.setState(SiteState.STANDBY_ERROR);
                        coordinator.persistServiceConfiguration(site.toConfiguration());
                    }
                    // update the vdc config version on the new active site.
                    drUtil.updateVdcTargetVersion(site.getUuid(), SiteInfo.DR_OP_FAILOVER, vdcTargetVersion,
                            oldActiveSite.getUuid(), currentSite.getUuid());
                }
            }

            drUtil.updateVdcTargetVersion(uuid, SiteInfo.DR_OP_FAILOVER, vdcTargetVersion, oldActiveSite.getUuid(), currentSite.getUuid());
            coordinator.commitTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN,
                    oldActiveSite.toBriefString(), currentSite.toBriefString());
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Error happened when failover at site {}", uuid, e);
            coordinator.discardTransaction();
            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_FAILURE, null,
                    currentSite.getName(), currentSite.getVipEndPoint());
            throw APIException.internalServerErrors.failoverFailed(currentSite.getName(), e.getMessage());
        }
    }

    /**
     * This is internal API to do precheck for failover
     * 
     * @return return response with error message and service code
     */
    @POST
    @Path("/internal/failoverprecheck")
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public FailoverPrecheckResponse failoverPrecheck() {
        log.info("Precheck for failover internally");

        FailoverPrecheckResponse response = new FailoverPrecheckResponse();
        response.setSite(this.siteMapper.map(drUtil.getLocalSite()));
        try {
            precheckForFailover();
        } catch (InternalServerErrorException e) {
            log.warn("Failed to precheck failover", e);
            response.setErrorMessage(e.getMessage());
            response.setServiceCode(e.getServiceCode().ordinal());
            return response;
        } catch (Exception e) {
            log.error("Failed to precheck failover", e);
            response.setErrorMessage(e.getMessage());
            return response;
        }

        return response;
    }

    /**
     * This is internal API to do failover
     * 
     * @return return response with error message and service code
     */
    @POST
    @Path("/internal/failover")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response failover(@QueryParam("newActiveSiteUUid") String newActiveSiteUUID,
            @QueryParam("oldActiveSiteUUid") String oldActiveSiteUUID, @QueryParam("vdcVersion") String vdcTargetVersion) {
        log.info("Begin to failover internally with newActiveSiteUUid {}, oldActiveSiteUUid {}", newActiveSiteUUID, oldActiveSiteUUID);

        Site currentSite = drUtil.getLocalSite();
        String uuid = currentSite.getUuid();

        try {
            // set state
            Site oldActiveSite = new Site();
            if (StringUtils.isEmpty(oldActiveSiteUUID)) {
                log.info("Cant't find active site id, go on to do failover");
            } else {
                oldActiveSite = drUtil.getSiteFromLocalVdc(oldActiveSiteUUID);
                drUtil.removeSite(oldActiveSite);
            }
            
            Site newActiveSite = drUtil.getSiteFromLocalVdc(newActiveSiteUUID);
            newActiveSite.setState(SiteState.STANDBY_FAILING_OVER);
            coordinator.persistServiceConfiguration(newActiveSite.toConfiguration());

            drUtil.updateVdcTargetVersion(currentSite.getUuid(), SiteInfo.DR_OP_FAILOVER, Long.parseLong(vdcTargetVersion), oldActiveSite.getUuid(), currentSite.getUuid());

            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_SUCCESS, AuditLogManager.AUDITOP_BEGIN,
                    oldActiveSite.toBriefString(), newActiveSite.toBriefString());
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Error happened when failover at site %s", uuid, e);
            auditDisasterRecoveryOps(OperationTypeEnum.FAILOVER, AuditLogManager.AUDITLOG_FAILURE, null, uuid, currentSite.getVipEndPoint(),
                    currentSite.getName());
            throw APIException.internalServerErrors.failoverFailed(currentSite.getName(), e.getMessage());
        }
    }

    /**
     * Update site information. Only name and description can be updated.
     * 
     * @param uuid target site uuid
     * @param siteParam site information
     * @return
     */
    @PUT
    @Path("/{uuid}")
    @Consumes({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN }, blockProxies = true)
    public Response updateSite(@PathParam("uuid") String uuid, SiteUpdateParam siteParam) {
        log.info("Begin to update site information for {}", uuid);
        Site site = null;

        try {
            site = drUtil.getSiteFromLocalVdc(uuid);
        } catch (RetryableCoordinatorException e) {
            log.error("Can't find site with specified site UUID {}", uuid);
            throw APIException.badRequests.siteIdNotFound();
        }

        if (!validSiteName(siteParam.getName())) {
            throw APIException.internalServerErrors.updateSiteFailed(site.getName(),
                    String.format("Site name should not be empty or longer than %d characters.", SITE_NAME_LENGTH_LIMIT));
        }

        for (Site eachSite : drUtil.listSites()) {
            if (eachSite.getUuid().equals(uuid)) {
                continue;
            }

            if (eachSite.getName().equals(siteParam.getName())) {
                throw APIException.internalServerErrors.addStandbyPrecheckFailed("Duplicate site name");
            }
        }

        try {
            site.setName(siteParam.getName());
            site.setDescription(siteParam.getDescription());
            coordinator.persistServiceConfiguration(site.toConfiguration());

            auditDisasterRecoveryOps(OperationTypeEnum.UPDATE_SITE, AuditLogManager.AUDITLOG_SUCCESS, null, site.getName(), site.getVipEndPoint(),
                    site.getUuid());
            return Response.status(Response.Status.ACCEPTED).build();
        } catch (Exception e) {
            log.error("Error happened when update site %s", uuid, e);
            auditDisasterRecoveryOps(OperationTypeEnum.UPDATE_SITE, AuditLogManager.AUDITLOG_FAILURE, null, site.getName(), site.getVipEndPoint(),
                    site.getUuid());
            throw APIException.internalServerErrors.updateSiteFailed(site.getName(), e.getMessage());
        }
    }

    private boolean validSiteName(String siteName) {
        if (!StringUtils.isBlank(siteName) && siteName.length() <= SITE_NAME_LENGTH_LIMIT) {
            return true;
        }
        return false;
    }

    private boolean isDataSynced(Site site) {
        if (site.getState().equals(SiteState.ACTIVE)) {
            return true;
        } 
        
        if (site.getState().equals(SiteState.STANDBY_SYNCED)) {
            SiteMonitorResult monitorResult = coordinator.getTargetInfo(site.getUuid(), SiteMonitorResult.class);
            if (monitorResult != null && monitorResult.getDbQuorumLostSince() > 0) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Query the details, such as transition timings, for specific standby site
     * 
     * @param uuid site UUID
     * @return SiteActionsTime with detail information
     */
    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @CheckPermission(roles = { Role.SECURITY_ADMIN, Role.RESTRICTED_SECURITY_ADMIN,
            Role.SYSTEM_ADMIN, Role.RESTRICTED_SYSTEM_ADMIN, Role.SYSTEM_MONITOR })
    @Path("/{uuid}/details")
    public SiteDetailRestRep getSiteDetails(@PathParam("uuid") String uuid) {
        log.info("Begin to get site paused time by uuid {}", uuid);

        SiteDetailRestRep standbyDetails = new SiteDetailRestRep();
        try {
            Site standby = drUtil.getSiteFromLocalVdc(uuid);

            standbyDetails.setCreationTime(new Date(standby.getCreationTime()));
            Double latency = drUtil.getSiteNetworkState(uuid).getNetworkLatencyInMs();
            standbyDetails.setNetworkLatencyInMs(latency);
            Date lastSyncTime = drUtil.getLastSyncTime(standby);
            if (lastSyncTime != null) {
                standbyDetails.setLastSyncTime(lastSyncTime);
            }
            standbyDetails.setDataSynced(isDataSynced(standby));

            ClusterInfo.ClusterState clusterState = coordinator.getControlNodesState(standby.getUuid());
            if(clusterState != null) {
                standbyDetails.setClusterState(clusterState.toString());
            }
            else {
                standbyDetails.setClusterState(ClusterInfo.ClusterState.UNKNOWN.toString());
            }

            standbyDetails.setSiteState(standby.getState().toString());
        } catch (CoordinatorException e) {
            log.error("Can't find site {} from ZK", uuid);
            throw APIException.badRequests.siteIdNotFound();
        } catch (Exception e) {
            log.error("Find find site from ZK for UUID {} : {}" + uuid, e);
        }

        return standbyDetails;
    }

    @GET
    @Produces({ MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    @Path("/internal/list")
    public SiteList getSitesInternally() {
        return this.getSites();
    }

    /**
     * Common precheck logic for DR operations.
     *
     * @param excludedSiteIds, site ids to exclude from the cluster state precheck
     */
    private void commonPrecheck(List<String> excludedSiteIds) {
        if (drUtil.isStandby()) {
            throw APIException.badRequests.operationOnlyAllowedOnActiveSite();
        }
        if (!isClusterStable()) {
            throw APIException.serviceUnavailable.clusterStateNotStable();
        }

        for (Site site : drUtil.listStandbySites()) {
            if (excludedSiteIds.contains(site.getUuid())) {
                continue;
            }
            // don't check node state for paused sites.
            if (site.getState().equals(SiteState.STANDBY_PAUSED) || site.getState().equals(SiteState.ACTIVE_DEGRADED)) {
                continue;
            }
            int nodeCount = site.getNodeCount();

            ClusterInfo.ClusterState state = coordinator.getControlNodesState(site.getUuid());
            // state could be null
            if (!ClusterInfo.ClusterState.STABLE.equals(state)) {
                log.error("Site {} is not stable {}", site.getUuid(), Objects.toString(state));
                throw APIException.serviceUnavailable.siteClusterStateNotStable(site.getName(), Objects.toString(state));
            }
        }
    }

    /**
     * Wrapper for commonPrecheck that enforce precheck on all sites
     *
     */
    private void commonPrecheck() {
        commonPrecheck(new ArrayList<String>());
    }

    private Site validateSiteConfig(String uuid) {
        if (!isClusterStable()) {
            log.error("Cluster is unstable");
            throw APIException.serviceUnavailable.clusterStateNotStable();
        }

        try {
            return drUtil.getSiteFromLocalVdc(uuid);
        } catch (CoordinatorException e) {
            log.error("Can't find site {} from ZK", uuid);
            throw APIException.badRequests.siteIdNotFound();
        }
    }

    private void precheckForGeo() {
        Map<String, List<Site>> vdcSiteMap = drUtil.getVdcSiteMap();
        int numOfVdcs = vdcSiteMap.keySet().size();
        if (numOfVdcs > 1) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Not allowed to add standby site in multivdc configuration");
        }
    }
    
    /*
     * Internal method to check whether standby can be attached to current active site
     */
    protected void precheckForStandbyAdd(SiteConfigRestRep standby, ViPRCoreClient viprCoreClient) {
        if (!isClusterStable()) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Current site is not stable");
        }

        if (!standby.isClusterStable()) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Remote site is not stable");
        }

        // standby should be refresh install
        if (!standby.isFreshInstallation() && !SiteState.ACTIVE_DEGRADED.toString().equalsIgnoreCase(standby.getState())) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Standby is not a fresh installation");
        }

        // DB schema version should be same
        String currentDbSchemaVersion = coordinator.getCurrentDbSchemaVersion();
        String standbyDbSchemaVersion = standby.getDbSchemaVersion();
        if (!currentDbSchemaVersion.equalsIgnoreCase(standbyDbSchemaVersion)) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(String.format(
                    "Standby db schema version %s is not same as active site %s",
                    standbyDbSchemaVersion, currentDbSchemaVersion));
        }

        // this site should not be standby site
        String activeId = drUtil.getActiveSite().getUuid();
        if (activeId != null && !activeId.equals(coordinator.getSiteId())) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("This site is also a standby site");
        }

        checkSupportedIPForAttachStandby(standby);
        
        checkNATForAttachStandby(viprCoreClient);
    }

    private void checkNATForAttachStandby(ViPRCoreClient viprCoreClient) {
        DualInetAddress inetAddress = coordinator.getInetAddessLookupMap().getDualInetAddress();
        String ipv4 = inetAddress.getInet4();
        String ipv6 = inetAddress.getInet6();

        log.info("Got local node's IP addresses, IPv4 = {}, IPv6 = {}", ipv4, ipv6);

        DRNatCheckParam checkParam = new DRNatCheckParam();
        checkParam.setIPv4Address(ipv4);
        checkParam.setIPv6Address(ipv6);
        DRNatCheckResponse resp = viprCoreClient.site().checkIfBehindNat(checkParam);
        if (resp.isBehindNAT()) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(String
                    .format("The remote site sees this node's IP as %s, which is different from the local addresses: %s or %s, it may be behind a NAT.",
                            resp.getSeenIp(), ipv4, ipv6));
        }
    }

    protected void checkSupportedIPForAttachStandby(SiteConfigRestRep standby) {
        Site site = drUtil.getLocalSite();

        // active has IPv4 and standby has no IPv4
        if (!isHostIPAddressMapEmpty(site.getHostIPv4AddressMap()) && isHostIPAddressMapEmpty(standby.getHostIPv4AddressMap())) {
            throw APIException.internalServerErrors
                    .addStandbyPrecheckFailed("Unsupported network configuration. Active site has IPv4. Standby site should be IPv4 or dual stack ");
        }

        // active has only IPv6 and standby has IPv4
        if (isHostIPAddressMapEmpty(site.getHostIPv4AddressMap()) && !isHostIPAddressMapEmpty(standby.getHostIPv4AddressMap())) {
            throw APIException.internalServerErrors
                    .addStandbyPrecheckFailed("Unsupported network configuration. Active site only has IPv6, Standby site should not has IPv4 address");
        }
    }

    private boolean isHostIPAddressMapEmpty(Map<String, String> map) {
        if (map == null) {
            return true;
        }
        
        for (String ip : map.values()) {
            if (!PropertyConstants.IPV4_ADDR_DEFAULT.equals(ip) && !PropertyConstants.IPV6_ADDR_DEFAULT.equals(ip)) {
                return false;
            }
        }

        return true;
    }

    protected void precheckStandbyVersion(SiteAddParam standby) {
        ViPRSystemClient viprSystemClient = createViPRSystemClient(standby.getVip(), standby.getUsername(), standby.getPassword());

        // software version should be matched
        SoftwareVersion currentSoftwareVersion;
        SoftwareVersion standbySoftwareVersion;
        try {
            currentSoftwareVersion = coordinator.getTargetInfo(RepositoryInfo.class).getCurrentVersion();
            standbySoftwareVersion = new SoftwareVersion(viprSystemClient.upgrade().getTargetVersion().getTargetVersion());
        } catch (Exception e) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(String.format("Fail to get software version %s",
                    e.getMessage()));
        }

        // enforcing a strict match between active/standby software versions
        // otherwise the standby site will automatically upgrade/downgrade to the same version with the active site
        if (!currentSoftwareVersion.equals(standbySoftwareVersion)) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(String.format(
                    "Standby site version %s does not equal to current version %s",
                    standbySoftwareVersion, currentSoftwareVersion));
        }
    }

    /*
     * Internal method to check whether failover from active to standby is allowed
     */
    protected void precheckForSwitchover(String standbyUuid) {
        Site standby = null;

        if (drUtil.isStandby()) {
            throw APIException.badRequests.operationOnlyAllowedOnActiveSite();
        }

        try {
            standby = drUtil.getSiteFromLocalVdc(standbyUuid);
        } catch (CoordinatorException e) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getUuid(),
                    "Standby uuid is not valid, can't find it");
        }

        if (standbyUuid.equals(drUtil.getActiveSite().getUuid())) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Can't switchover to an active site");
        }

        if (!drUtil.isSiteUp(standbyUuid)) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Standby site is not up");
        }

        if (standby.getState() != SiteState.STANDBY_SYNCED) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Standby site is not fully synced");
        }

        List<Site> existingSites = drUtil.listSites();
        for (Site site : existingSites) {
            ClusterInfo.ClusterState state = coordinator.getControlNodesState(site.getUuid());
            if (state != ClusterInfo.ClusterState.STABLE) {
                log.info("Site {} is not stable {}", site.getUuid(), state);
                throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(),
                        String.format("Site %s is not stable", site.getName()));
            }
        }
    }

    /*
     * Internal method to check whether failover to standby is allowed
     */
    private void precheckForFailoverLocally(String standbyUuid) {
        Site standby = drUtil.getLocalSite();

        // API should be only send to local site
        if (!standby.getUuid().equals(standbyUuid)) {
            throw APIException.internalServerErrors.failoverPrecheckFailed(standby.getName(),
                    String.format("Failover can only be executed in local site. Local site uuid %s is not matched with uuid %s",
                            standby.getUuid(), standbyUuid));
        }

        String uuid = drUtil.getActiveSite().getUuid();
        if (!StringUtils.isEmpty(uuid)) {
            SiteNetworkState networkState = drUtil.getSiteNetworkState(uuid);
            if (networkState.getNetworkHealth() != NetworkHealth.BROKEN) {
                throw APIException.internalServerErrors.failoverPrecheckFailed(standby.getName(),
                        "Active site is still available");
            }
        }

        // should be PAUSED, either marked by itself or user
        // Also allow user to failover to an ACTIVE_DEGRADED site
        if (standby.getState() != SiteState.STANDBY_PAUSED && standby.getState() != SiteState.ACTIVE_DEGRADED) {
            throw APIException.internalServerErrors.failoverPrecheckFailed(standby.getName(),
                    "Please wait for this site to recognize the Active site is down and automatically switch to a Paused state before failing over.");
        }

        // Need to check every site state via HMAC way when failing over to ACTIVE_DEGRADED site
        if (standby.getState() == SiteState.ACTIVE_DEGRADED) {
            for (Site site : drUtil.listSites()) {
                if (!site.getUuid().equals(drUtil.getLocalSite().getUuid()) && isSiteAvailable(site)) {
                    throw APIException.internalServerErrors.failoverPrecheckFailed(standby.getName(),
                            String.format("Site %s is available, so it's not allowed to failover to an ACTIVE_DEGRADED site", site.getName()));
                }
            }
        }

        precheckForFailover();
    }

    /**
     * Reuse /site/internal/list API to check if it can return result correctly
     * @return true if result can be returned correctly, otherwise return false
     */
    private boolean isSiteAvailable(Site site) {
        try (InternalSiteServiceClient client = new InternalSiteServiceClient(site, coordinator, apiSignatureGenerator)) {
            SiteList sites = client.getSiteList();
            if (!sites.getSites().isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Error happened when trying to get sites from site {} via HMAC way", site.getUuid(), e);
        }
        return false;
    }

    void precheckForFailover() {
        Site standby = drUtil.getLocalSite();
        String standbyUuid = standby.getUuid();
        String standbyName = standby.getName();

        // show be only standby
        if (drUtil.isActiveSite()) {
            throw APIException.badRequests.operationNotAllowedOnActiveSite();
        }

        // all syssvc should be up
        if (!drUtil.isAllSyssvcUp(standbyUuid)) {
            log.info("Not all syssvc is running at site {}", standby.getName());
            throw APIException.internalServerErrors.failoverPrecheckFailed(standby.getName(),
                    String.format("Site %s is not stable, one or more syssvc is not running", standby.getName()));
        }

        // Make sure that the local ZK has been reconfigured to participant
        // This DOES NOT implies that the active site is unreachable, notably when the local site is manually paused
        String coordinatorMode = drUtil.getLocalCoordinatorMode();
        log.info("Local coordinator mode is {}", coordinatorMode);
        if (coordinatorMode == null || !drUtil.isParticipantNode(coordinatorMode)) {
            log.info("Active site is available now, can't do failover");
            throw APIException.internalServerErrors.failoverPrecheckFailed(standbyName, "Active site is available now, can't do failover");
        }
    }

    protected SiteRestRep findRecommendFailoverSite(List<SiteRestRep> responseSiteFromRemote, Site currentSite) {

        if (currentSite.getState().equals(SiteState.STANDBY_SYNCED)) {
            return this.siteMapper.map(currentSite);
        }

        for (SiteRestRep site : responseSiteFromRemote) {
            if (site != null && SiteState.STANDBY_SYNCED.toString().equalsIgnoreCase(site.getState())) {
                return site;
            }
        }

        return this.siteMapper.map(currentSite);
    }

    protected void validateAddParam(SiteAddParam param, List<Site> existingSites) {
        String siteName = param.getName();
        if (!validSiteName(siteName)) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed(String.format(
                    "Site name should not be empty or longer than %d characters.", SITE_NAME_LENGTH_LIMIT));
        }
        String siteVip = param.getVip();
        InetAddress address = null;
        try {
            address = InetAddress.getByName(siteVip);
        } catch (UnknownHostException e) {
            throw APIException.internalServerErrors.addStandbyPrecheckFailed("Could not resolve target standby site virtual IP.  Please check name service.");
        }
        if (address.getHostAddress().contains(":")) {
            param.setVip(DualInetAddress.normalizeInet6Address(address.getHostAddress()));
        } else {
            param.setVip(address.getHostAddress());
        }
        log.info("Target standby site ip is {}", param.getVip());

        for (Site site : existingSites) {
            if (site.getName().equals(siteName)) {
                throw APIException.internalServerErrors.addStandbyPrecheckFailed("Duplicate site name");
            }

            // COP-18954 Skip stability check for paused sites
            if (site.getState().equals(SiteState.STANDBY_PAUSED)) {
                continue;
            }

            ClusterInfo.ClusterState state = coordinator.getControlNodesState(site.getUuid());
            if (state != ClusterInfo.ClusterState.STABLE) {
                log.info("Site {} is not stable {}", site.getUuid(), state);
                throw APIException.internalServerErrors.addStandbyPrecheckFailed(String.format("Currently site %s is not stable", site.getName()));
            }
        }
    }

    private String generateShortId(List<Site> existingSites) throws Exception {
        Set<String> existingShortIds = new HashSet<String>();
        for (Site site : existingSites) {
            existingShortIds.add(site.getSiteShortId());
        }

        for (int i = 1; i < MAX_NUM_OF_STANDBY; i++) {
            String id = String.format(SHORTID_FMT, i);
            if (!existingShortIds.contains(id)) {
                return id;
            }
        }
        throw new Exception("Failed to generate standby short id");
    }

    protected boolean isClusterStable() {
        return coordinator.getControlNodesState() == ClusterInfo.ClusterState.STABLE;
    }

    protected boolean isFreshInstallation() {
        Configuration setupConfig = coordinator.queryConfiguration(InitialSetup.CONFIG_KIND, InitialSetup.CONFIG_ID);

        boolean freshInstall = (setupConfig == null) || !Boolean.parseBoolean(setupConfig.getConfig(InitialSetup.COMPLETE));
        log.info("Fresh installation {}", freshInstall);

        boolean hasDataInDB = dbClient.hasUsefulData();
        log.info("Has useful data in DB {}", hasDataInDB);

        return freshInstall && !hasDataInDB;
    }

    // encapsulate the create ViPRCoreClient operation for easy UT writing because need to mock ViPRCoreClient
    protected ViPRCoreClient createViPRCoreClient(String vip, String username, String password) {
        try {
            return new ViPRCoreClient(vip, true).withLogin(username, password);
        } catch (Exception e) {
            log.error(String.format("Fail to create vipr client, vip: %s, username: %s", vip, username), e);
            throw APIException.internalServerErrors.failToCreateViPRClient();
        }
    }

    // encapsulate the create ViPRSystemClient operation for easy UT writing because need to mock ViPRSystemClient
    protected ViPRSystemClient createViPRSystemClient(String vip, String username, String password) {
        try {
            return new ViPRSystemClient(vip, true).withLogin(username, password);
        } catch (Exception e) {
            log.error(String.format("Fail to create vipr client, vip: %s, username: %s", vip, username), e);
            throw APIException.internalServerErrors.failToCreateViPRClient();
        }
    }
    
    // encapsulate the create InternalSiteServiceClient operation for easy UT writing because need to mock InternalSiteServiceClient
    protected InternalSiteServiceClient createInternalSiteServiceClient(Site site) {
        return new InternalSiteServiceClient(site);
    }

    public void setApiSignatureGenerator(InternalApiSignatureKeyGenerator apiSignatureGenerator) {
        this.apiSignatureGenerator = apiSignatureGenerator;
    }

    public void setSiteMapper(SiteMapper siteMapper) {
        this.siteMapper = siteMapper;
    }

    public void setSysUtils(SysUtils sysUtils) {
        this.sysUtils = sysUtils;
    }

    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    public void setCoordinator(CoordinatorClient coordinator) {
        this.coordinator = coordinator;
    }

    public void setDrUtil(DrUtil drUtil) {
        this.drUtil = drUtil;
    }

    public void setIpsecConfig(IPsecConfig ipsecConfig) {
        this.ipsecConfig = ipsecConfig;
    }

    private void startLeaderSelector() {
        LeaderSelector leaderSelector = coordinator.getLeaderSelector(coordinator.getSiteId(), Constants.FAILBACK_DETECT_LEADER,
                new FailbackLeaderSelectorListener());
        leaderSelector.autoRequeue();
        leaderSelector.start();
    }
    
    protected void precheckForSwitchoverForActiveSite(String standbyUuid) {
        Site standby = null;

        if (drUtil.isStandby()) {
            throw APIException.badRequests.operationOnlyAllowedOnActiveSite();
        }

        try {
            standby = drUtil.getSiteFromLocalVdc(standbyUuid);
        } catch (CoordinatorException e) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getUuid(),
                    "Standby uuid is not valid, can't find it");
        }

        if (standbyUuid.equals(drUtil.getActiveSite().getUuid())) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Can't switchover to an active site");
        }

        if (standby.getState() != SiteState.STANDBY_SYNCED) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Standby site is not fully synced");
        }
        
        if (!drUtil.isSiteUp(standbyUuid)) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Standby site is not up");
        }
        
        if (coordinator.getControlNodesState(standby.getUuid()) != ClusterInfo.ClusterState.STABLE) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Standby site is not stable");
        }
        
        if (!isClusterStable()) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), "Active site is not stable");
        }
        
        checkSiteConnectivity(standby);
        
        List<Site> existingSites = drUtil.listStandbySites();
        for (Site site : existingSites) {
            if (site.getState() != SiteState.STANDBY_SYNCED && site.getState() != SiteState.STANDBY_PAUSED) {
                throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(), String.format("Standby site %s is not synced or paused", site.getName()));
            }
            
            ClusterInfo.ClusterState state = coordinator.getControlNodesState(site.getUuid());
            if (site.getState() != SiteState.STANDBY_PAUSED && state != ClusterInfo.ClusterState.STABLE) {
                log.info("Site {} is not stable {}", site.getUuid(), state);
                throw APIException.internalServerErrors.switchoverPrecheckFailed(standby.getName(),
                        String.format("Site %s is not stable", site.getName()));
            }
        }
    }

    private void precheckForSwitchoverForLocalStandby() {
        if (!isClusterStable()) {
            throw APIException.serviceUnavailable.clusterStateNotStable();
        }

        Site currentSite = drUtil.getLocalSite();
        if (currentSite.getState() != SiteState.STANDBY_SYNCED && currentSite.getState() != SiteState.STANDBY_PAUSED) {
            throw APIException.internalServerErrors.switchoverPrecheckFailed(currentSite.getName(),
                    String.format("Standby site %s is not synced or paused", currentSite.getName()));
        }
    }
    
    private void checkSiteConnectivity(Site site) {
        SiteNetworkState networkState = drUtil.getSiteNetworkState(site.getUuid());
        if (networkState.getNetworkHealth() == NetworkHealth.BROKEN) {
            throw APIException.internalServerErrors.siteConnectionBroken(site.getName(), "Network health state is broken.");
        }
        
        if (drUtil.testPing(site.getVip(), SITE_CONNECTION_TEST_PORT, SITE_CONNECT_TEST_TIMEOUT) == -1) {
            throw APIException.internalServerErrors.siteConnectionBroken(site.getName(),
                    String.format("Can't connect to site by virtual IP: %s", site.getVip()));
        }
    }

    private class FailbackLeaderSelectorListener extends LeaderSelectorListenerImpl {

        private static final int FAILBACK_DETECT_INTERNVAL_SECONDS = 60;
        private ScheduledExecutorService service;

        @Override
        protected void startLeadership() throws Exception {
            log.info("This node is selected as failback detector");

            service = Executors.newScheduledThreadPool(1);
            service.scheduleAtFixedRate(failbackDetectMonitor, 0, FAILBACK_DETECT_INTERNVAL_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        protected void stopLeadership() {
            service.shutdown();
            try {
                while (!service.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.info("Waiting scheduler thread pool to shutdown for another 30s");
                }
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting to shutdown scheduler thread pool.", e);
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isSiteContainedBy(String siteId, SiteList sites) {
        for (SiteRestRep site : sites.getSites()) {
            if (siteId.equals(site.getUuid())) {
                return true;
            }
        }
        log.info("Site {} is removed", siteId);
        return false;
    }

    private Runnable failbackDetectMonitor = new Runnable() {

        @Override
        public void run() {
            try {
                if (!needCheckFailback() || !isLocalSiteDiscarded()) {
                    log.info("No need to check failback locally or there's no remote active site, return");
                    return;
                }

                if(!resetActiveSite()) {
                    log.error("Failed to reset active site status info");
                    return;
                }

                degradeActiveSite();
            } catch (Exception e) {
                log.error("Error occured during failback detect monitor", e);
            }
        }
        
        private void degradeActiveSite() throws Exception {
            try {
                log.info("Current active site {}", drUtil.getActiveSite().getUuid());
                coordinator.startTransaction();
                
                List<Site> standbySites = drUtil.listStandbySites();
                for (Site standbySite : standbySites) {
                    if (!drUtil.isLocalSite(standbySite)) {
                        log.info("Set standby site {} from state {} to STANDBY_PAUSED", standbySite.getUuid(), standbySite.getState());
                        standbySite.setState(SiteState.STANDBY_PAUSED);
                        coordinator.persistServiceConfiguration(standbySite.toConfiguration());
                    }
                }

                // At this moment this site is disconnected with others, so ok to have own vdc version.
                drUtil.updateVdcTargetVersion(coordinator.getSiteId(), SiteInfo.DR_OP_FAILBACK_DEGRADE, DrUtil.newVdcConfigVersion());

                coordinator.commitTransaction();
            } catch (Exception e) {
                coordinator.discardTransaction();
                throw e;
            }
        }

        private boolean needCheckFailback() {
            Site localSite = drUtil.getLocalSite();
            if (localSite.getState().equals(SiteState.ACTIVE)) {
                log.info("Current site is active site, need to check failback");
                return true;
            }

            if (localSite.getState().equals(SiteState.ACTIVE_DEGRADED)) {
                log.info("Site is already ACTIVE_FAILBACK_DEGRADED");
                if (!coordinator.locateAllServices(localSite.getUuid(), "controllersvc", "1", null, null).isEmpty()) {
                    log.info("there are some controller service alive, process to degrade");
                    return true;
                }

                if (!coordinator.locateAllServices(localSite.getUuid(), "sasvc", "1", null, null).isEmpty()) {
                    log.info("there are some sa service alive, process to degrade");
                    return true;
                }

                if (!coordinator.locateAllServices(localSite.getUuid(), "vasasvc", "1", null, null).isEmpty()) {
                    log.info("there are some vasa service alive, process to degrade");
                    return true;
                }
            }

            log.info("Current site is not active, and there is no alive controllersvc/sasvc/vasasvc, so no need to check failback");
            return false;
        }

        /**
         * @return true when Local site is in ACTIVE_DEGRADED state or can't be found according returned result from other site
         */
        private boolean isLocalSiteDiscarded() {
            String localSiteId = drUtil.getLocalSite().getUuid();
            for (Site remoteSite : drUtil.listStandbySites()) {
                if (drUtil.isSiteUp(remoteSite.getUuid()) || remoteSite.getState() == SiteState.ACTIVE_DEGRADED) {
                    log.info("Site {} is up or in ACTIVE_DEGRADED state, skip checking it", remoteSite.getUuid());
                    continue;
                }
                try (InternalSiteServiceClient client = new InternalSiteServiceClient(remoteSite, coordinator, apiSignatureGenerator)) {
                    SiteList sites = client.getSiteList();
                    if (!isSiteContainedBy(localSiteId, sites) || isSiteDegraded(localSiteId, sites)) {
                        log.info("Local site {} is in ACTIVE_DEGRADED state or removed according data returned from site {}", localSiteId, remoteSite.getUuid());
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("Failed to check remote site information during failback detect", e);
                    continue;
                }
            }
            return false;
        }

        /*
         * reset the new active site's status info in the local active_degraded site (old active site)
         */
        private boolean resetActiveSite() {
            String localSiteId = drUtil.getLocalSite().getUuid();
            for (Site remoteSite : drUtil.listStandbySites()) {
                if (drUtil.isSiteUp(remoteSite.getUuid()) || remoteSite.getState() == SiteState.ACTIVE_DEGRADED) {
                    log.info("Site {} is up or in ACTIVE_DEGRADED state, skip checking it", remoteSite.getUuid());
                    continue;
                }
                try (InternalSiteServiceClient client = new InternalSiteServiceClient(remoteSite, coordinator, apiSignatureGenerator)) {
                    SiteList sites = client.getSiteList();

                    String remoteSiteStatus ="";
                    String localSiteStatus = SiteState.ACTIVE_DEGRADED.toString();
                    for (SiteRestRep site : sites.getSites()) {
                        if (remoteSite.getUuid().equals(site.getUuid())) {
                            remoteSiteStatus = site.getState();
                        }
                        if (localSiteId.equals(site.getUuid())) {
                            localSiteStatus = site.getState();
                        }
                    }

                    if (SiteState.ACTIVE_DEGRADED.toString().equals(localSiteStatus) &&
                        SiteState.ACTIVE.toString().equals(remoteSiteStatus)) {
                        log.info("Local site {} is in ACTIVE_DEGRADED state according data returned from site {}", localSiteId, remoteSite.getUuid());
                        log.info("Remote site {} is in ACTIVE state according data returned from site {}", remoteSite.getUuid(), remoteSite.getUuid());

                        log.info("Setting active site status information in the local active degraded site");
                        Site newActiveSite = drUtil.getSiteFromLocalVdc(remoteSite.getUuid());
                        newActiveSite.setState(SiteState.ACTIVE);
                        coordinator.persistServiceConfiguration(newActiveSite.toConfiguration());

                        // update local site to degraded to avoid 2 actives in the DR config
                        Site localSite = drUtil.getLocalSite();
                        SiteState lastState = localSite.getState();
                        localSite.setState(SiteState.ACTIVE_DEGRADED);
                        localSite.setLastState(lastState);
                        coordinator.persistServiceConfiguration(localSite.toConfiguration());
                        
                        return true;
                    }
                } catch (Exception e) {
                    log.warn("Failed to set active site information in the local active degraded site", e);
                    continue;
                }
            }
            return false;
        }

        private boolean isSiteContainedBy(String siteId, SiteList sites) {
            for (SiteRestRep site : sites.getSites()) {
                if (siteId.equals(site.getUuid())) {
                    return true;
                }
            }
            log.info("Site {} is removed", siteId);
            return false;
        }

        private boolean isSiteDegraded(String siteId, SiteList sites) {
            for (SiteRestRep site : sites.getSites()) {
                if (siteId.equals(site.getUuid()) && SiteState.ACTIVE_DEGRADED.toString().equals(site.getState())) {
                    log.info("Site {} is ACTIVE_DEGRADED", siteId);
                    return true;
                }
            }
            return false;
        }

    };
}
