/** **************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 * *
 * http://www.apache.org/licenses/LICENSE-2.0                 *
 * *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 * ***************************************************************/
package org.apache.james.task.eventsourcing

import java.io.Closeable
import java.time.Duration
import java.util

import javax.inject.Inject

import org.apache.james.eventsourcing.eventstore.{EventStore, History}
import org.apache.james.eventsourcing.{AggregateId, Subscriber}
import org.apache.james.task.TaskManager.ReachedTimeoutException
import org.apache.james.task._
import org.apache.james.task.eventsourcing.TaskCommand._

import com.google.common.annotations.VisibleForTesting
import reactor.core.publisher.{Flux, Mono}
import reactor.core.scheduler.Schedulers

class EventSourcingTaskManager @Inject @VisibleForTesting private[eventsourcing](
                                                                                  workQueueSupplier: WorkQueueSupplier,
                                                                                  val eventStore: EventStore,
                                                                                  val executionDetailsProjection: TaskExecutionDetailsProjection,
                                                                                  val hostname: Hostname,
                                                                                  val terminationSubscriber: TerminationSubscriber) extends TaskManager with Closeable {

  private def workDispatcher: Subscriber = {
    case Created(aggregateId, _, task, _) =>
      val taskWithId = new TaskWithId(aggregateId.taskId, task)
      workQueue.submit(taskWithId)
    case CancelRequested(aggregateId, _, _) =>
      workQueue.cancel(aggregateId.taskId)
    case _ =>
  }

  import scala.collection.JavaConverters._

  private val loadHistory: AggregateId => History = eventStore.getEventsOfAggregate _
  private val eventSourcingSystem = ScalaEventSourcingSystem(
    handlers = Set(
      new CreateCommandHandler(loadHistory, hostname),
      new StartCommandHandler(loadHistory, hostname),
      new RequestCancelCommandHandler(loadHistory, hostname),
      new CompleteCommandHandler(loadHistory),
      new CancelCommandHandler(loadHistory),
      new FailCommandHandler(loadHistory)),
    subscribers = Set(
      executionDetailsProjection.asSubscriber(hostname),
      workDispatcher,
      terminationSubscriber),
    eventStore = eventStore)

  private val workQueue: WorkQueue = workQueueSupplier(eventSourcingSystem)

  override def submit(task: Task): TaskId = {
    val taskId = TaskId.generateTaskId
    val command = Create(taskId, task)
    eventSourcingSystem.dispatch(command)
    taskId
  }

  override def getExecutionDetails(id: TaskId): TaskExecutionDetails = executionDetailsProjection.load(id)
    .getOrElse(throw new TaskNotFoundException())

  override def list: util.List[TaskExecutionDetails] = listScala.asJava

  override def list(status: TaskManager.Status): util.List[TaskExecutionDetails] = listScala
    .filter(details => details.getStatus == status)
    .asJava

  private def listScala: List[TaskExecutionDetails] = executionDetailsProjection
    .list
    .flatMap(details => executionDetailsProjection.load(details.taskId))

  override def cancel(id: TaskId): Unit = {
    val command = RequestCancel(id)
    eventSourcingSystem.dispatch(command)
  }

  @throws(classOf[TaskNotFoundException])
  @throws(classOf[ReachedTimeoutException])
  override def await(id: TaskId, timeout: Duration): TaskExecutionDetails = {
    try {
      val details = Mono.fromSupplier[TaskExecutionDetails](() => getExecutionDetails(id))
        .filter(_.getStatus.isFinished)

      val findEvent = Flux.from(terminationSubscriber.listenEvents)
        .filter {
          case event: TaskEvent => event.getAggregateId.taskId == id
          case _ => false
        }
        .next()
        .then(details)

      Flux.merge(findEvent, details)
        .subscribeOn(Schedulers.boundedElastic)
        .blockFirst(timeout)
    } catch {
      case _: IllegalStateException => throw new ReachedTimeoutException
    }
  }

  override def close(): Unit = {
    workQueue.close()
  }
}
