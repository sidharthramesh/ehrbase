/*
 * Modifications copyright (C) 2019 Christian Chevalley, Vitasystems GmbH,  Hannover Medical School,
 * Jake Smolka (Hannover Medical School), and Luis Marco-Ruiz (Hannover Medical School).

 * This file is part of Project EHRbase

 * Copyright (c) 2015 Christian Chevalley
 * This file is part of Project Ethercis
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.dao.access.jooq;

import com.nedap.archie.rm.generic.AuditDetails;
import org.ehrbase.api.definitions.ServerConfig;
import org.ehrbase.api.exception.InternalServerException;
import org.ehrbase.dao.access.interfaces.*;
import org.ehrbase.dao.access.interfaces.I_ConceptAccess.ContributionChangeType;
import org.ehrbase.dao.access.jooq.party.PersistedPartyProxy;
import org.ehrbase.dao.access.support.DataAccess;
import org.ehrbase.dao.access.util.ContributionDef;
import org.ehrbase.dao.access.util.TransactionTime;
import org.ehrbase.ehr.knowledge.I_KnowledgeCache;
import org.ehrbase.jooq.pg.Routines;
import org.ehrbase.jooq.pg.enums.ContributionDataType;
import org.ehrbase.jooq.pg.enums.ContributionState;
import org.ehrbase.jooq.pg.tables.AdminDeleteStatusHistory;
import org.ehrbase.jooq.pg.tables.records.AdminDeleteStatusRecord;
import org.ehrbase.jooq.pg.tables.records.AdminGetLinkedCompositionsForContribRecord;
import org.ehrbase.jooq.pg.tables.records.AdminGetLinkedStatusForContribRecord;
import org.ehrbase.jooq.pg.tables.records.ContributionRecord;
import org.ehrbase.service.IntrospectService;
import org.jooq.DSLContext;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.UUID;

import static org.ehrbase.jooq.pg.Tables.CONTRIBUTION;

/**
 * Created by Christian Chevalley on 4/17/2015.
 */
public class ContributionAccess extends DataAccess implements I_ContributionAccess {

  Logger log = LoggerFactory.getLogger(ContributionAccess.class);
    private ContributionRecord contributionRecord;
    private I_AuditDetailsAccess auditDetails; // audit associated with this contribution

    /**
     * Basic constructor for contribution.
     * @param context DB context object of current server context
     * @param knowledgeManager Knowledge cache object of current server context
     * @param introspectCache Introspect cache object of current server context
     * @param serverConfig Server config object of current server context
     * @param ehrId Given ID of EHR this contribution will be created for
     */
    public ContributionAccess(DSLContext context, I_KnowledgeCache knowledgeManager, IntrospectService introspectCache, ServerConfig serverConfig, UUID ehrId) {

        super(context, knowledgeManager, introspectCache, serverConfig);

        this.contributionRecord = context.newRecord(CONTRIBUTION);

        contributionRecord.setEhrId(ehrId);

        // create and attach new minimal audit instance to this contribution
        this.auditDetails = I_AuditDetailsAccess.getInstance(this.getDataAccess());
    }

    /**
     * Constructor with convenient {@link I_DomainAccess} parameter, for better readability.
     * @param domainAccess Current domain access object
     * @param ehrId Given ID of EHR this contribution will be created for
     */
    public ContributionAccess(I_DomainAccess domainAccess, UUID ehrId) {

        super(domainAccess.getContext(), domainAccess.getKnowledgeManager(), domainAccess.getIntrospectService(), domainAccess.getServerConfig());

        this.contributionRecord = domainAccess.getContext().newRecord(CONTRIBUTION);

        contributionRecord.setEhrId(ehrId);

        // create and attach new minimal audit instance to this contribution
        this.auditDetails = I_AuditDetailsAccess.getInstance(this.getDataAccess());
    }

    // internal minimal constructor - needs proper initialization before following usage
    private ContributionAccess(I_DomainAccess domainAccess) {
        super(domainAccess);
    }

    /**
     * @throws InternalServerException on failed fetching of contribution
     */
    public static I_ContributionAccess retrieveInstance(I_DomainAccess domainAccess, UUID contributionId) {

        ContributionAccess contributionAccess = new ContributionAccess(domainAccess);

        try {
            contributionAccess.contributionRecord = domainAccess.getContext().fetchOne(CONTRIBUTION, CONTRIBUTION.ID.eq(contributionId));
        } catch (Exception e) {
            throw new InternalServerException("fetching contribution failed", e);
        }

        if (contributionAccess.contributionRecord == null)
            return null;

        // also retrieve attached audit
        contributionAccess.auditDetails = new AuditDetailsAccess(domainAccess.getDataAccess()).retrieveInstance(domainAccess.getDataAccess(), contributionAccess.getHasAuditDetails());

        return contributionAccess;

    }

    @Override
    public UUID commit(Timestamp transactionTime) {

        // first create DB entry of auditDetails so they can get referenced in this contribution
        UUID auditId = this.auditDetails.commit();
        contributionRecord.setHasAudit(auditId);

        if (contributionRecord.getState() == ContributionState.incomplete) {
            log.warn("Contribution state has not been set");
        }

        contributionRecord.setEhrId(this.getEhrId());
        if (contributionRecord.insert() == 0)
            throw new InternalServerException("Couldn't store contribution");

        return contributionRecord.getId();
    }

    @Override
    public UUID commit() {
        return commit(TransactionTime.millis());
    }

    /**
     * Commit the contribution with optional values, excluding audit, which needs to be created and set beforehand.
     */
    @Override
    public UUID commit(Timestamp transactionTime, ContributionDataType contributionType, ContributionDef.ContributionState state) {

        if (transactionTime == null) {
            transactionTime = TransactionTime.millis();
        }

        //set contribution attributes
        setContributionDataType(Objects.requireNonNullElse(contributionType, ContributionDataType.other));

        setState(Objects.requireNonNullElse(state, ContributionDef.ContributionState.COMPLETE));

        return commit(transactionTime);
    }

    /**
     * Commit the contribution with (optional) given values (incl. audit data, which is handled embedded)
     * @throws InternalServerException when contribution couldn't be created because of an internal problem
     */
    @Override
    public UUID commit(Timestamp transactionTime, UUID committerId, UUID systemId, ContributionDataType contributionType, ContributionDef.ContributionState state, I_ConceptAccess.ContributionChangeType contributionChangeType, String description) {
        // create new audit_details instance for this contribution
        this.auditDetails = I_AuditDetailsAccess.getInstance(this.getDataAccess());

        if (transactionTime == null) {
            transactionTime = TransactionTime.millis();
        }

        //set contribution attributes
        setContributionDataType(Objects.requireNonNullElse(contributionType, ContributionDataType.other));

        setState(Objects.requireNonNullElse(state, ContributionDef.ContributionState.COMPLETE));

        // audit attributes
        if (committerId != null) {
            auditDetails.setCommitter(committerId);
        } else {
            throw new InternalServerException("Missing mandatory committer ID");
        }

        if (systemId != null) {
            auditDetails.setSystemId(systemId);
        } else {
            throw new InternalServerException("Missing mandatory system ID");
        }

        if (contributionChangeType != null)
            auditDetails.setChangeType(I_ConceptAccess.fetchContributionChangeType(this, contributionChangeType.name()));
        else
            auditDetails.setChangeType(I_ConceptAccess.fetchContributionChangeType(this, I_ConceptAccess.ContributionChangeType.CREATION));

        if (description != null) {
            auditDetails.setDescription(description);
        }
        return commit(transactionTime);
    }

    @Override
    public Boolean update(Timestamp transactionTime, UUID committerId, UUID systemId, String contributionType, String contributionState, String contributionChangeType, String description) {
        //set contribution  attributes
        ContributionDataType type = null;
        ContributionDef.ContributionState state = null;
        I_ConceptAccess.ContributionChangeType changeType = null;

        if (contributionType == null)
            type = ContributionDataType.valueOf(contributionType);

        if (contributionState != null)
            state = ContributionDef.ContributionState.valueOf(contributionState);

        if (contributionChangeType != null)
            changeType = I_ConceptAccess.ContributionChangeType.valueOf(contributionChangeType);

        // audit handling will be executed centralized in the following called method
        return update(transactionTime, committerId, systemId, type, state, changeType, description);
    }

    @Override
    public Boolean update(Timestamp transactionTime, UUID committerId, UUID systemId, ContributionDataType contributionType, ContributionDef.ContributionState state, I_ConceptAccess.ContributionChangeType contributionChangeType, String description) {
        //set contribution  attributes
        if (contributionType != null)
            setContributionDataType(contributionType);
        if (state != null)
            setState(state);

        // embedded audit handling
        this.auditDetails = I_AuditDetailsAccess.getInstance(getDataAccess()); // new audit for new action
        if (committerId != null)
            this.auditDetails.setCommitter(committerId);
        if (systemId != null)
            this.auditDetails.setSystemId(systemId);
        if (description != null)
            this.auditDetails.setDescription(description);
        if (contributionChangeType != null)
            this.auditDetails.setChangeType(I_ConceptAccess.fetchContributionChangeType(this, contributionChangeType));

        return update(transactionTime);
    }

    @Override
    public UUID commitWithSignature(String signature) {
        contributionRecord.setSignature(signature);
        contributionRecord.setState(ContributionState.valueOf("complete"));
        contributionRecord.store();

        return contributionRecord.getId();
    }

    @Override
    public UUID updateWithSignature(String signature) {
        contributionRecord.setSignature(signature);
        contributionRecord.setState(ContributionState.valueOf("complete"));
        contributionRecord.update();

        return contributionRecord.getId();
    }

    @Override
    public Boolean update(Timestamp transactionTime) {
        return update(transactionTime, false);
    }

    @Override
    public Boolean update(Timestamp transactionTime, boolean force) {
        boolean updated = false;

        if (force || contributionRecord.changed()) { // TODO-447: test if this creates an own audit for contributions

            if (!contributionRecord.changed()) {
                //hack: force tell jOOQ to perform updateComposition whatever...
                contributionRecord.changed(true);
            }

            // update contribution's audit with modification change type and execute update of it, too
            this.auditDetails.setChangeType(I_ConceptAccess.fetchContributionChangeType(this, I_ConceptAccess.ContributionChangeType.MODIFICATION));
            if (this.auditDetails.update(transactionTime, force).equals(Boolean.FALSE))
                throw new InternalServerException("Couldn't update auditDetails");
            contributionRecord.setHasAudit(this.auditDetails.getId());  // new audit ID

            // execute update of contribution itself
            contributionRecord.setId(UUID.randomUUID());    // force to create new entry from old values
            updated = contributionRecord.insert() == 1;
        }

        return updated;
    }

    @Override
    public Boolean update() {
        return update(TransactionTime.millis());
    }

    @Override
    public Boolean update(Boolean force) {
        return update(TransactionTime.millis());
    }

    @Override
    public Integer delete() {
        int count = 0;
        //delete contribution record
        count += contributionRecord.delete();

        return count;
    }

    /**
     * @throws InternalServerException on failed fetching of contribution
     */
    public I_ContributionAccess retrieve(UUID id) {
        return retrieveInstance(this, id);
    }


    @Override
    public UUID getContributionId() {
        return contributionRecord.getId();
    }

    @Override
    public void setAuditDetailsChangeType(UUID changeType) {
        auditDetails.setChangeType(changeType);
    }

    @Override
    public ContributionDataType getContributionDataType() {
        return contributionRecord.getContributionType();
    }

    @Override
    public void setContributionDataType(ContributionDataType contributionDataType) {
        contributionRecord.setContributionType(contributionDataType);
    }

    @Override
    public void setState(ContributionDef.ContributionState state) {
        if (state != null)
            contributionRecord.setState(ContributionState.valueOf(state.getLiteral()));
    }

    @Override
    public void setComplete() {
        contributionRecord.setState(ContributionState.valueOf(ContributionState.complete.getLiteral()));
    }

    @Override
    public void setIncomplete() {
        contributionRecord.setState(ContributionState.valueOf(ContributionState.incomplete.getLiteral()));
    }

    @Override
    public void setDeleted() {
        contributionRecord.setState(ContributionState.valueOf(ContributionState.deleted.getLiteral()));
    }

    @Override
    public void setAuditDetailsValues(UUID committer, UUID system, String description, ContributionChangeType changeType) {
        if (committer == null || system == null || changeType == null)
            throw new IllegalArgumentException("arguments not optional");
        auditDetails.setCommitter(committer);
        auditDetails.setSystemId(system);
        auditDetails.setChangeType(I_ConceptAccess.fetchContributionChangeType(this, changeType));

        if (description != null)
            auditDetails.setDescription(description);
    }

    @Override
    public void setAuditDetailsValues(AuditDetails auditObject) {
        // parse
        UUID committer = new PersistedPartyProxy(this).getOrCreate(auditObject.getCommitter());
        UUID system = I_SystemAccess.createOrRetrieveInstanceId(this, null, auditObject.getSystemId());
        UUID changeType = I_ConceptAccess.fetchContributionChangeType(this, auditObject.getChangeType().getValue());

        // set
        if (committer == null || system == null)
            throw new IllegalArgumentException("arguments not optional");
        auditDetails.setCommitter(committer);
        auditDetails.setSystemId(system);
        auditDetails.setChangeType(changeType);

        // optional description
        if (auditObject.getDescription() != null)
            auditDetails.setDescription(auditObject.getDescription().getValue());
    }

    @Override
    public void setAuditDetailsCommitter(UUID committer) {
        auditDetails.setCommitter(committer);
    }

    @Override
    public void setAuditDetailsSystemId(UUID system) {
        auditDetails.setSystemId(system);
    }

    @Override
    public void setAuditDetailsDescription(String description) {
        auditDetails.setDescription(description);
    }

    @Override
    public UUID getAuditsCommitter() {
        return auditDetails.getCommitter();
    }

    @Override
    public UUID getAuditsSystemId() {
        return auditDetails.getSystemId();
    }

    @Override
    public String getAuditsDescription() {
        return auditDetails.getDescription();
    }

    @Override
    public ContributionChangeType getAuditsChangeType() {
        return I_ConceptAccess.ContributionChangeType.valueOf(auditDetails.getChangeType().getLiteral().toUpperCase());
    }

    @Override
    public ContributionDef.ContributionType getContributionType() {
        return ContributionDef.ContributionType.valueOf(contributionRecord.getContributionType().getLiteral());
    }

    @Override
    public ContributionDef.ContributionState getContributionState() {
        return ContributionDef.ContributionState.valueOf(contributionRecord.getState().getLiteral());
    }

    @Override
    public UUID getEhrId() {
        return contributionRecord.getEhrId();
    }

    @Override
    public String getDataType() {
        return contributionRecord.getContributionType().getLiteral();
    }

    @Override
    public void setDataType(ContributionDataType contributionDataType) {
        contributionRecord.setContributionType(contributionDataType);
    }

    @Override
    public UUID getId() {
        return contributionRecord.getId();
    }

    @Override
    public void setEhrId(UUID ehrId) {
        contributionRecord.setEhrId(ehrId);
    }

    @Override
    public DataAccess getDataAccess() {
        return this;
    }

    @Override
    public void setHasAuditDetails(UUID auditId) {
        contributionRecord.setHasAudit(auditId);
    }

    @Override
    public UUID getHasAuditDetails() {
        return contributionRecord.getHasAudit();
    }

    @Override
    public void adminDelete() {
        AdminApiUtils adminApi = new AdminApiUtils(getContext());

        // retrieve info on all linked versioned_objects
        Result<AdminGetLinkedCompositionsForContribRecord> linkedCompositions = Routines.adminGetLinkedCompositionsForContrib(getContext().configuration(), this.getId());
        Result<AdminGetLinkedStatusForContribRecord> linkedStatus = Routines.adminGetLinkedStatusForContrib(getContext().configuration(), this.getId());

        // handling of linked composition
        linkedCompositions.forEach(compo -> adminApi.deleteComposition(compo.getComposition()));

        // handling of linked status
        linkedStatus.forEach(status -> {
            Result<AdminDeleteStatusRecord> delStatus = Routines.adminDeleteStatus(getContext().configuration(), status.getStatus());
            if (delStatus.isEmpty()) {
                throw new InternalServerException("Admin deletion of Status failed! Unexpected result.");
            }
            // handle auxiliary objects
            delStatus.forEach(id -> {
                // delete status audit
                adminApi.deleteAudit(id.getStatusAudit(), "Status", false);

                // clear history
                int res = getContext().selectQuery(new AdminDeleteStatusHistory().call(status.getStatus())).execute();
                if (res != 1)
                    throw new InternalServerException("Admin deletion of Status failed!");
            });

        });

        // delete contribution itself
        adminApi.deleteContribution(this.getId(), null, false);
    }
}
