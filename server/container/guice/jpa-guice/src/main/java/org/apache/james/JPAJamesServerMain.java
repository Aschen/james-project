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

package org.apache.james;

import org.apache.james.modules.MailboxModule;
import org.apache.james.modules.activemq.ActiveMQQueueModule;
import org.apache.james.modules.data.JPADataModule;
import org.apache.james.modules.data.SieveJPARepositoryModules;
import org.apache.james.modules.mailbox.DefaultEventModule;
import org.apache.james.modules.mailbox.JPAMailboxModule;
import org.apache.james.modules.mailbox.LuceneSearchMailboxModule;
import org.apache.james.modules.mailbox.MemoryDeadLetterModule;
import org.apache.james.modules.protocols.IMAPServerModule;
import org.apache.james.modules.protocols.LMTPServerModule;
import org.apache.james.modules.protocols.ManageSieveServerModule;
import org.apache.james.modules.protocols.POP3ServerModule;
import org.apache.james.modules.protocols.ProtocolHandlerModule;
import org.apache.james.modules.protocols.SMTPServerModule;
import org.apache.james.modules.server.DKIMMailetModule;
import org.apache.james.modules.server.DataRoutesModules;
import org.apache.james.modules.server.DefaultProcessorsConfigurationProviderModule;
import org.apache.james.modules.server.ElasticSearchMetricReporterModule;
import org.apache.james.modules.server.JMXServerModule;
import org.apache.james.modules.server.MailQueueRoutesModule;
import org.apache.james.modules.server.MailRepositoriesRoutesModule;
import org.apache.james.modules.server.MailboxRoutesModule;
import org.apache.james.modules.server.NoJwtModule;
import org.apache.james.modules.server.RawPostDequeueDecoratorModule;
import org.apache.james.modules.server.ReIndexingModule;
import org.apache.james.modules.server.SieveRoutesModule;
import org.apache.james.modules.server.SwaggerRoutesModule;
import org.apache.james.modules.server.WebAdminServerModule;
import org.apache.james.modules.spamassassin.SpamAssassinListenerModule;
import org.apache.james.server.core.configuration.Configuration;

import com.google.inject.Module;
import com.google.inject.util.Modules;

public class JPAJamesServerMain {

    public static final Module WEBADMIN = Modules.combine(
        new WebAdminServerModule(),
        new DataRoutesModules(),
        new MailboxRoutesModule(),
        new MailQueueRoutesModule(),
        new MailRepositoriesRoutesModule(),
        new SwaggerRoutesModule(),
        new SieveRoutesModule(),
        new ReIndexingModule());

    public static final Module PROTOCOLS = Modules.combine(
        new IMAPServerModule(),
        new LMTPServerModule(),
        new ManageSieveServerModule(),
        new POP3ServerModule(),
        new ProtocolHandlerModule(),
        new SMTPServerModule(),
        WEBADMIN);

    public static final Module JPA_SERVER_MODULE = Modules.combine(
        new ActiveMQQueueModule(),
        new DefaultProcessorsConfigurationProviderModule(),
        new ElasticSearchMetricReporterModule(),
        new JPADataModule(),
        new JPAMailboxModule(),
        new MailboxModule(),
        new LuceneSearchMailboxModule(),
        new NoJwtModule(),
        new RawPostDequeueDecoratorModule(),
        new SieveJPARepositoryModules(),
        new DefaultEventModule(),
        new MemoryDeadLetterModule(),
        new SpamAssassinListenerModule());

    public static final Module JPA_MODULE_AGGREGATE = Modules.combine(JPA_SERVER_MODULE, PROTOCOLS);

    public static void main(String[] args) throws Exception {
        Configuration configuration = Configuration.builder()
            .useWorkingDirectoryEnvProperty()
            .build();

        GuiceJamesServer server = GuiceJamesServer.forConfiguration(configuration)
                    .combineWith(JPA_MODULE_AGGREGATE,
                            new JMXServerModule(),
                            new DKIMMailetModule());
        server.start();
    }

}
