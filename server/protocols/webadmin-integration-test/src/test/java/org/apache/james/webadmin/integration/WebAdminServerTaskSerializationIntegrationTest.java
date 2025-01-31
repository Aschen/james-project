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

package org.apache.james.webadmin.integration;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static io.restassured.RestAssured.with;
import static org.apache.james.webadmin.Constants.SEPARATOR;
import static org.apache.james.webadmin.vault.routes.DeletedMessagesVaultRoutes.MESSAGE_PATH_PARAM;
import static org.apache.james.webadmin.vault.routes.DeletedMessagesVaultRoutes.USERS;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.stream.Stream;

import javax.mail.Flags;

import org.apache.james.CassandraRabbitMQAwsS3JmapTestRule;
import org.apache.james.DockerCassandraRule;
import org.apache.james.GuiceJamesServer;
import org.apache.james.backends.cassandra.versions.CassandraSchemaVersionManager;
import org.apache.james.backends.cassandra.versions.SchemaVersion;
import org.apache.james.core.User;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.events.Event;
import org.apache.james.mailbox.events.EventDeadLetters;
import org.apache.james.mailbox.events.GenericGroup;
import org.apache.james.mailbox.events.Group;
import org.apache.james.mailbox.events.MailboxListener;
import org.apache.james.mailbox.inmemory.InMemoryId;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.MailboxConstants;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.probe.MailboxProbe;
import org.apache.james.mailbox.store.event.EventFactory;
import org.apache.james.mailrepository.api.MailRepository;
import org.apache.james.mailrepository.api.MailRepositoryStore;
import org.apache.james.mailrepository.api.MailRepositoryUrl;
import org.apache.james.modules.EventDeadLettersProbe;
import org.apache.james.modules.MailboxProbeImpl;
import org.apache.james.probe.DataProbe;
import org.apache.james.task.TaskManager;
import org.apache.james.utils.DataProbeImpl;
import org.apache.james.utils.MailRepositoryProbeImpl;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.CassandraMailboxMergingRoutes;
import org.apache.james.webadmin.routes.CassandraMappingsRoutes;
import org.apache.james.webadmin.routes.MailQueueRoutes;
import org.apache.james.webadmin.routes.MailRepositoriesRoutes;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.vault.routes.DeletedMessagesVaultRoutes;
import org.apache.mailet.base.test.FakeMail;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.eclipse.jetty.http.HttpStatus;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WebAdminServerTaskSerializationIntegrationTest {

    private static final String DOMAIN = "domain";
    private static final String USERNAME = "username@" + DOMAIN;

    @Rule
    public DockerCassandraRule cassandra = new DockerCassandraRule();

    @Rule
    public CassandraRabbitMQAwsS3JmapTestRule jamesTestRule = CassandraRabbitMQAwsS3JmapTestRule.defaultTestRule();

    private GuiceJamesServer guiceJamesServer;
    private DataProbe dataProbe;
    private MailboxProbe mailboxProbe;

    @Before
    public void setUp() throws Exception {
        guiceJamesServer = jamesTestRule.jmapServer(cassandra.getModule());
        guiceJamesServer.start();
        dataProbe = guiceJamesServer.getProbe(DataProbeImpl.class);
        dataProbe.addDomain(DOMAIN);
        WebAdminGuiceProbe webAdminGuiceProbe = guiceJamesServer.getProbe(WebAdminGuiceProbe.class);

        mailboxProbe = guiceJamesServer.getProbe(MailboxProbeImpl.class);

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminGuiceProbe.getWebAdminPort())
            .build();
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @After
    public void tearDown() {
        guiceJamesServer.stop();
    }

    @Test
    public void fullReindexingShouldCompleteWhenNoMail() {
        String taskId = with()
            .post("/mailboxes?task=reIndex")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(notNullValue()))
            .body("type", is("FullReIndexing"))
            .body("additionalInformation.successfullyReprocessedMailCount", is(0))
            .body("additionalInformation.failedReprocessedMailCount", is(0))
            .body("additionalInformation.failures", is(anEmptyMap()));
    }

    @Test
    public void deleteMailsFromMailQueueShouldCompleteWhenSenderIsValid() {
        String firstMailQueue = with()
                .basePath(MailQueueRoutes.BASE_URL)
            .get()
            .then()
                .statusCode(HttpStatus.OK_200)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getString("[0]");

        String taskId = with()
                .basePath(MailQueueRoutes.BASE_URL)
                .param("sender", USERNAME)
            .delete(firstMailQueue + "/mails")
                .jsonPath()
                .getString("taskId");

        given()
                .basePath(TasksRoutes.BASE)
            .when()
                .get(taskId + "/await")
            .then()
                .body("status", is("completed"))
                .body("taskId", is(notNullValue()))
                .body("type", is("delete-mails-from-mail-queue"))
                .body("additionalInformation.mailQueueName", is(notNullValue()))
                .body("additionalInformation.remainingCount", is(0))
                .body("additionalInformation.initialCount", is(0))
                .body("additionalInformation.sender", is(USERNAME))
                .body("additionalInformation.name", is(nullValue()))
                .body("additionalInformation.recipient", is(nullValue()))
        ;
    }

    @Test
    public void reprocessingAllMailsShouldComplete() {
        String escapedRepositoryPath = with()
                .basePath(MailRepositoriesRoutes.MAIL_REPOSITORIES)
            .get()
            .then()
                .statusCode(HttpStatus.OK_200)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getString("[0].path");

        String taskId = with()
                .basePath(MailRepositoriesRoutes.MAIL_REPOSITORIES)
                .param("action", "reprocess")
            .patch(escapedRepositoryPath + "/mails")
            .then()
                .statusCode(HttpStatus.CREATED_201)
                .extract()
                .jsonPath()
                .getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(notNullValue()))
            .body("type", is("reprocessingAllTask"))
            .body("additionalInformation.repositoryPath", is(notNullValue()))
            .body("additionalInformation.targetQueue", is(notNullValue()))
            .body("additionalInformation.targetProcessor", is(nullValue()))
            .body("additionalInformation.initialCount", is(0))
            .body("additionalInformation.remainingCount", is(0));
    }

    @Test
    public void reprocessingOneMailShouldCreateATask() throws Exception {
        MailRepositoryStore mailRepositoryStore = guiceJamesServer.getProbe(MailRepositoryProbeImpl.class).getMailRepositoryStore();
        Stream<MailRepositoryUrl> urls = mailRepositoryStore.getUrls();
        MailRepositoryUrl mailRepositoryUrl = urls.findAny().get();
        MailRepository repository = mailRepositoryStore.get(mailRepositoryUrl).get();

        String mailKey = "name1";
        repository.store(FakeMail.builder()
            .name(mailKey)
            .mimeMessage(MimeMessageBuilder.mimeMessageBuilder().build())
            .build());

        String taskId = with()
            .basePath(MailRepositoriesRoutes.MAIL_REPOSITORIES)
            .param("action", "reprocess")
        .patch(mailRepositoryUrl.urlEncoded() + "/mails/name1")
        .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("failed"))
            .body("taskId", is(notNullValue()))
            .body("type", is("reprocessingOneTask"))
            .body("additionalInformation.repositoryPath", is(mailRepositoryUrl.asString()))
            .body("additionalInformation.targetQueue", is(notNullValue()))
            .body("additionalInformation.mailKey", is(mailKey))
            .body("additionalInformation.targetProcessor", is(nullValue()));
    }

    @Test
    public void singleMessageReindexingShouldCompleteWhenMail() throws Exception {
        MailboxId mailboxId = mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX);
        ComposedMessageId composedMessageId = mailboxProbe.appendMessage(
                USERNAME,
                MailboxPath.forUser(USERNAME, MailboxConstants.INBOX),
                new ByteArrayInputStream("Subject: test\r\n\r\ntestmail".getBytes()),
                new Date(),
                false,
                new Flags());

        String taskId = with()
            .post("/mailboxes/" + mailboxId.serialize() + "/mails/"
                    + composedMessageId.getUid().asLong() + "?task=reIndex")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("messageReIndexing"))
            .body("additionalInformation.mailboxId", is(mailboxId.serialize()))
            .body("additionalInformation.uid", is(Math.toIntExact(composedMessageId.getUid().asLong())));
    }

    @Test
    public void messageIdReIndexingShouldCompleteWhenMail() throws Exception {
        mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX);
        ComposedMessageId composedMessageId = mailboxProbe.appendMessage(
            USERNAME,
            MailboxPath.forUser(USERNAME, MailboxConstants.INBOX),
            new ByteArrayInputStream("Subject: test\r\n\r\ntestmail".getBytes()),
            new Date(),
            false,
            new Flags());

        String taskId = with()
            .post("/messages/" + composedMessageId.getMessageId().serialize() + "?task=reIndex")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("MessageIdReIndexingTask"))
            .body("additionalInformation.messageId", is(composedMessageId.getMessageId().serialize()));
    }

    @Test
    public void userReindexingShouldComplete() {
        String taskId = with()
                .queryParam("user", USERNAME)
                .queryParam("task", "reIndex")
            .post("/mailboxes")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("userReIndexing"))
            .body("additionalInformation.successfullyReprocessedMailCount", is(0))
            .body("additionalInformation.failedReprocessedMailCount", is(0))
            .body("additionalInformation.user", is(USERNAME))
            .body("additionalInformation.failures", is(anEmptyMap()));
    }

    @Test
    public void deletedMessageVaultRestoreShouldComplete() throws Exception {
        dataProbe.addUser(USERNAME, "password");
        String query =
            "{" +
                "  \"fieldName\": \"subject\"," +
                "  \"operator\": \"contains\"," +
                "  \"value\": \"subject contains\"" +
                "}";

        String taskId =
            with()
                .basePath(DeletedMessagesVaultRoutes.ROOT_PATH)
                .queryParam("action", "restore")
                .body(query)
            .post(USERS + SEPARATOR + USERNAME)
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("deletedMessages/restore"))
            .body("additionalInformation.user", is(USERNAME))
            .body("additionalInformation.successfulRestoreCount", is(0))
            .body("additionalInformation.errorRestoreCount", is(0));
    }

    @Test
    public void deletedMessageVaultExportShouldComplete() throws Exception {
        dataProbe.addUser(USERNAME, "password");
        String query = "{" +
            "\"combinator\": \"and\"," +
            "\"criteria\": []" +
            "}";

        String exportTo = "exportTo@james.org";
        String taskId = with()
            .basePath(DeletedMessagesVaultRoutes.ROOT_PATH)
            .queryParam("action", "export")
            .queryParam("exportTo", exportTo)
            .body(query)
        .post(USERS + SEPARATOR + USERNAME)
        .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("deletedMessages/export"))
            .body("additionalInformation.userExportFrom", is(USERNAME))
            .body("additionalInformation.exportTo", is(exportTo))
            .body("additionalInformation.totalExportedMessages", is(0));
    }

    @Test
    public void errorRecoveryIndexationShouldCompleteWhenNoMail() {
        String taskId = with()
            .post("/mailboxes?task=reIndex")
            .jsonPath()
            .get("taskId");

        with()
            .basePath(TasksRoutes.BASE)
            .get(taskId + "/await");

        String fixingTaskId = with()
            .queryParam("reIndexFailedMessagesOf", taskId)
            .queryParam("task", "reIndex")
        .post("/mailboxes")
        .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(fixingTaskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("ErrorRecoveryIndexation"))
            .body("additionalInformation.successfullyReprocessedMailCount", is(0))
            .body("additionalInformation.failedReprocessedMailCount", is(0))
            .body("additionalInformation.failures", is(anEmptyMap()));
    }

    @Test
    public void eventDeadLettersRedeliverShouldComplete() {
        String taskId = with()
            .queryParam("action", "reDeliver")
        .post("/events/deadLetter")
            .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("eventDeadLettersRedeliverTask"))
            .body("additionalInformation.successfulRedeliveriesCount", is(0))
            .body("additionalInformation.failedRedeliveriesCount", is(0));

    }

    @Test
    public void eventDeadLettersRedeliverShouldCreateATask() {
        String uuid = "6e0dd59d-660e-4d9b-b22f-0354479f47b4";
        String insertionUuid = "6e0dd59d-660e-4d9b-b22f-0354479f47b7";
        Group group = new GenericGroup("a");
        EventDeadLetters.InsertionId insertionId = EventDeadLetters.InsertionId.of(insertionUuid);
        MailboxListener.MailboxAdded event = EventFactory.mailboxAdded()
            .eventId(Event.EventId.of(uuid))
            .user(User.fromUsername(USERNAME))
            .sessionId(MailboxSession.SessionId.of(452))
            .mailboxId(InMemoryId.of(453))
            .mailboxPath(MailboxPath.forUser(USERNAME, "Important-mailbox"))
            .build();

        guiceJamesServer
            .getProbe(EventDeadLettersProbe.class)
            .getEventDeadLetters()
            .store(group, event, insertionId)
            .block();

        String taskId = with()
            .queryParam("action", "reDeliver")
        .post("/events/deadLetter/groups/" + group.asString())
        .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("failed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("eventDeadLettersRedeliverTask"))
            .body("additionalInformation.successfulRedeliveriesCount", is(0))
            .body("additionalInformation.failedRedeliveriesCount", is(0))
            .body("additionalInformation.group", is(group.asString()));
    }

    @Test
    public void postRedeliverSingleEventShouldCreateATask() {
        String uuid = "6e0dd59d-660e-4d9b-b22f-0354479f47b4";
        String insertionUuid = "6e0dd59d-660e-4d9b-b22f-0354479f47b7";
        Group group = new GenericGroup("a");
        EventDeadLetters.InsertionId insertionId = EventDeadLetters.InsertionId.of(insertionUuid);
        MailboxListener.MailboxAdded event = EventFactory.mailboxAdded()
            .eventId(Event.EventId.of(uuid))
            .user(User.fromUsername(USERNAME))
            .sessionId(MailboxSession.SessionId.of(452))
            .mailboxId(InMemoryId.of(453))
            .mailboxPath(MailboxPath.forUser(USERNAME, "Important-mailbox"))
            .build();

        guiceJamesServer
            .getProbe(EventDeadLettersProbe.class)
            .getEventDeadLetters()
            .store(group, event, insertionId)
            .block();

        String taskId = with()
            .queryParam("action", "reDeliver")
        .post("/events/deadLetter/groups/" + group.asString() + "/" + insertionUuid)
        .then()
            .statusCode(HttpStatus.CREATED_201)
            .extract()
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("failed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("eventDeadLettersRedeliverTask"))
            .body("additionalInformation.successfulRedeliveriesCount", is(0))
            .body("additionalInformation.failedRedeliveriesCount", is(0))
            .body("additionalInformation.group", is(group.asString()))
            .body("additionalInformation.insertionId", is(insertionId.getId().toString()));
    }

    @Test
    public void clearMailQueueShouldCompleteWhenNoQueryParameters() {
        String firstMailQueue = with()
                .basePath(MailQueueRoutes.BASE_URL)
            .get()
            .then()
                .statusCode(HttpStatus.OK_200)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getString("[0]");

        String taskId = with()
                .basePath(MailQueueRoutes.BASE_URL)
            .delete(firstMailQueue + "/mails")
                .jsonPath()
                .getString("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("clear-mail-queue"))
            .body("additionalInformation.mailQueueName", is(notNullValue()))
            .body("additionalInformation.initialCount", is(0))
            .body("additionalInformation.remainingCount", is(0));
    }

    @Test
    public void blobStoreBasedGarbageCollectionShoudComplete() {
        String taskId =
            with()
                .basePath(DeletedMessagesVaultRoutes.ROOT_PATH)
                .queryParam("scope", "expired")
            .delete()
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("deletedMessages/blobStoreBasedGarbageCollection"))
            .body("additionalInformation.beginningOfRetentionPeriod", is(notNullValue()))
            .body("additionalInformation.deletedBuckets", is(empty()));
    }

    @Test
    public void clearMailRepositoryShouldComplete() {
        String escapedRepositoryPath = with()
                .basePath(MailRepositoriesRoutes.MAIL_REPOSITORIES)
            .get()
            .then()
                .statusCode(HttpStatus.OK_200)
                .contentType(ContentType.JSON)
                .extract()
                .body()
                .jsonPath()
                .getString("[0].path");

        String taskId = with()
                .basePath(MailRepositoriesRoutes.MAIL_REPOSITORIES)
            .delete(escapedRepositoryPath + "/mails")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("clearMailRepository"))
            .body("additionalInformation.repositoryPath", is(notNullValue()))
            .body("additionalInformation.initialCount", is(0))
            .body("additionalInformation.remainingCount", is(0));
    }


    @Test
    public void mailboxMergingShouldComplete() {
        MailboxId origin = mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX);
        MailboxId destination = mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX + "2");

        String taskId = given()
                .body("{" +
                    "    \"mergeOrigin\":\"" + origin.serialize() + "\"," +
                    "    \"mergeDestination\":\"" + destination.serialize() + "\"" +
                    "}")
            .post(CassandraMailboxMergingRoutes.BASE)
                .jsonPath()
                .getString("taskId");

        with()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is(TaskManager.Status.COMPLETED.getValue()))
            .body("taskId", is(taskId))
            .body("type", is("mailboxMerging"))
            .body("additionalInformation.oldMailboxId", is(origin.serialize()))
            .body("additionalInformation.newMailboxId", is(destination.serialize()))
            .body("additionalInformation.totalMessageCount", is(0))
            .body("additionalInformation.messageMovedCount", is(0))
            .body("additionalInformation.messageFailedCount", is(0));
    }

    @Test
    public void singleMailboxReindexingShouldComplete() {
        MailboxId mailboxId = mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX);

        String taskId = when()
            .post("/mailboxes/" + mailboxId.serialize() + "?task=reIndex")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(Matchers.notNullValue()))
            .body("type", is("mailboxReIndexing"))
            .body("additionalInformation.successfullyReprocessedMailCount", is(0))
            .body("additionalInformation.failedReprocessedMailCount", is(0))
            .body("additionalInformation.mailboxId", is(mailboxId.serialize()))
            .body("additionalInformation.failures", is(anEmptyMap()));
    }

    @Test
    public void deletedMessagesVaultDeleteShouldCompleteEvenNoDeletedMessageExisted() throws Exception {
        dataProbe.addUser(USERNAME, "password");
        mailboxProbe.createMailbox(MailboxConstants.USER_NAMESPACE, USERNAME, MailboxConstants.INBOX);
        ComposedMessageId composedMessageId = mailboxProbe.appendMessage(
            USERNAME,
            MailboxPath.forUser(USERNAME, MailboxConstants.INBOX),
            new ByteArrayInputStream("Subject: test\r\n\r\ntestmail".getBytes()),
            new Date(),
            false,
            new Flags());

        String taskId =
            with()
                .basePath(DeletedMessagesVaultRoutes.ROOT_PATH)
            .delete(USERS + SEPARATOR + USERNAME + SEPARATOR + MESSAGE_PATH_PARAM + SEPARATOR + composedMessageId.getMessageId().serialize())
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("deletedMessages/delete"))
            .body("additionalInformation.user", is(USERNAME))
            .body("additionalInformation.deleteMessageId", is(composedMessageId.getMessageId().serialize()));
    }

    @Test
    public void cassandraMigrationShouldComplete() {
        SchemaVersion toVersion = CassandraSchemaVersionManager.MAX_VERSION;
        String taskId = with()
                .body(String.valueOf(toVersion.getValue()))
            .post("cassandra/version/upgrade")
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("CassandraMigration"))
            .body("additionalInformation.toVersion", is(toVersion.getValue()));
    }

    @Test
    public void cassandraMappingsSolveInconsistenciesShouldComplete() {
        String taskId = with()
                .basePath(CassandraMappingsRoutes.ROOT_PATH)
                .queryParam("action", "SolveInconsistencies")
            .post()
                .jsonPath()
                .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("cassandraMappingsSolveInconsistencies"))
            .body("additionalInformation.successfulMappingsCount", is(0))
            .body("additionalInformation.errorMappingsCount", is(0));
    }


}