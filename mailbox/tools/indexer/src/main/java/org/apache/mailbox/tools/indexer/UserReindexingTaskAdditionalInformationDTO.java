/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.mailbox.tools.indexer;

import java.util.List;
import java.util.function.Function;

import org.apache.james.core.User;
import org.apache.james.json.DTOModule;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class UserReindexingTaskAdditionalInformationDTO implements AdditionalInformationDTO {

    public static final Function<MailboxId.Factory, AdditionalInformationDTOModule<UserReindexingTask.AdditionalInformation, UserReindexingTaskAdditionalInformationDTO>> SERIALIZATION_MODULE =
        factory ->
            DTOModule.forDomainObject(UserReindexingTask.AdditionalInformation.class)
                .convertToDTO(UserReindexingTaskAdditionalInformationDTO.class)
                .toDomainObjectConverter(dto -> new UserReindexingTask.AdditionalInformation(User.fromUsername(dto.getUser()),
                    dto.getSuccessfullyReprocessedMailCount(),
                    dto.getFailedReprocessedMailCount(),
                    ReprocessingContextInformationDTO.deserializeFailures(factory, dto.getFailures())))
                .toDTOConverter((details, type) -> new UserReindexingTaskAdditionalInformationDTO(type, details.getUser(), details.getSuccessfullyReprocessedMailCount(), details.getFailedReprocessedMailCount(),
                    ReprocessingContextInformationDTO.serializeFailures(details.failures())))
                .typeName(UserReindexingTask.USER_RE_INDEXING.asString())
                .withFactory(AdditionalInformationDTOModule::new);

    private final ReprocessingContextInformationDTO reprocessingContextInformationDTO;
    private final String user;

    @JsonCreator
    private UserReindexingTaskAdditionalInformationDTO(@JsonProperty("type") String type,
                                                       @JsonProperty("user") String user,
                                                       @JsonProperty("successfullyReprocessedMailCount") int successfullyReprocessedMailCount,
                                                       @JsonProperty("failedReprocessedMailCount") int failedReprocessedMailCount,
                                                       @JsonProperty("failures") List<ReprocessingContextInformationDTO.ReindexingFailureDTO> failures) {
        this.user = user;
        this.reprocessingContextInformationDTO = new ReprocessingContextInformationDTO(
            type,
            successfullyReprocessedMailCount,
            failedReprocessedMailCount, failures);
    }

    @Override
    public String getType() {
        return reprocessingContextInformationDTO.getType();
    }

    public String getUser() {
        return user;
    }

    public int getSuccessfullyReprocessedMailCount() {
        return reprocessingContextInformationDTO.getSuccessfullyReprocessedMailCount();
    }

    public int getFailedReprocessedMailCount() {
        return reprocessingContextInformationDTO.getFailedReprocessedMailCount();
    }

    public List<ReprocessingContextInformationDTO.ReindexingFailureDTO> getFailures() {
        return reprocessingContextInformationDTO.getFailures();
    }
}
