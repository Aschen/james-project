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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.List;

import org.apache.james.core.User;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.indexer.ReIndexingExecutionFailures;
import org.apache.james.mailbox.model.TestId;
import org.apache.james.server.task.json.JsonTaskAdditionalInformationsSerializer;
import org.apache.james.server.task.json.JsonTaskSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;

class UserReindexingTaskSerializationTest {

    private ReIndexerPerformer reIndexerPerformer;
    private JsonTaskSerializer taskSerializer;
    private JsonTaskAdditionalInformationsSerializer jsonAdditionalInformationSerializer;

    private final User user = User.fromUsername("foo@apache.org");
    private final int successfullyReprocessedMailCount = 42;
    private final int failedReprocessedMailCount = 2;
    private ReIndexingExecutionFailures reIndexingExecutionFailures;
    private final String serializedUserReindexingTask = "{\"type\": \"userReIndexing\", \"username\": \"foo@apache.org\"}";

    private final String SERIALIZED_ADDITIONAL_INFORMATION = "{\"type\": \"userReIndexing\", \"user\": \"foo@apache.org\", \"successfullyReprocessedMailCount\":42,\"failedReprocessedMailCount\":2,\"failures\":[{\"mailboxId\":\"1\",\"uids\":[10]},{\"mailboxId\":\"2\",\"uids\":[20]}]}";

    private final TestId mailboxId = TestId.of(1L);
    private final MessageUid messageUid = MessageUid.of(10L);
    private final ReIndexingExecutionFailures.ReIndexingFailure indexingFailure = new ReIndexingExecutionFailures.ReIndexingFailure(mailboxId, messageUid);

    private final TestId mailboxId2 = TestId.of(2L);
    private final MessageUid messageUid2 = MessageUid.of(20L);

    private final ReIndexingExecutionFailures.ReIndexingFailure indexingFailure2 = new ReIndexingExecutionFailures.ReIndexingFailure(mailboxId2, messageUid2);
    private final List<ReIndexingExecutionFailures.ReIndexingFailure> failures = ImmutableList.of(indexingFailure, indexingFailure2);

    @BeforeEach
    void setUp() {
        reIndexerPerformer = mock(ReIndexerPerformer.class);
        UserReindexingTask.Factory factory = new UserReindexingTask.Factory(reIndexerPerformer);
        taskSerializer = new JsonTaskSerializer(UserReindexingTaskDTO.MODULE.apply(factory));
        jsonAdditionalInformationSerializer = new JsonTaskAdditionalInformationsSerializer(
            UserReindexingTaskAdditionalInformationDTO
                .SERIALIZATION_MODULE
                .apply(new TestId.Factory()));

        reIndexingExecutionFailures = new ReIndexingExecutionFailures(ImmutableList.of(
            new ReIndexingExecutionFailures.ReIndexingFailure(mailboxId, messageUid),
            new ReIndexingExecutionFailures.ReIndexingFailure(mailboxId2, messageUid2)));
    }

    @Test
    void userReindexingShouldBeSerializable() throws JsonProcessingException {
        User user = User.fromUsername("foo@apache.org");
        UserReindexingTask task = new UserReindexingTask(reIndexerPerformer, user);

        assertThatJson(taskSerializer.serialize(task))
            .isEqualTo(serializedUserReindexingTask);
    }

    @Test
    void userReindexingShouldBeDeserializable() throws IOException {
        User user = User.fromUsername("foo@apache.org");
        UserReindexingTask task = new UserReindexingTask(reIndexerPerformer, user);

        assertThat(taskSerializer.deserialize(serializedUserReindexingTask))
            .isEqualToComparingOnlyGivenFields(task, "reIndexerPerformer");
    }


    @Test
    void additionalInformationShouldBeSerializable() throws JsonProcessingException {
        UserReindexingTask.AdditionalInformation details = new UserReindexingTask.AdditionalInformation(user, successfullyReprocessedMailCount, failedReprocessedMailCount, reIndexingExecutionFailures);
        assertThatJson(jsonAdditionalInformationSerializer.serialize(details)).isEqualTo(SERIALIZED_ADDITIONAL_INFORMATION);
    }

    @Test
    void additionalInformationShouldBeDeserializable() throws IOException {
        UserReindexingTask.AdditionalInformation details = new UserReindexingTask.AdditionalInformation(user, successfullyReprocessedMailCount, failedReprocessedMailCount, reIndexingExecutionFailures);
        assertThat(jsonAdditionalInformationSerializer.deserialize(SERIALIZED_ADDITIONAL_INFORMATION))
            .isEqualToComparingFieldByField(details);
    }
}

