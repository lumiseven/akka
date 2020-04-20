/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.internal.EventSourcedBehaviorImpl

class EventSourcedBehaviorTestKitImpl[Command, Event, State](actorTestKit: ActorTestKit, behavior: Behavior[Command])
    extends EventSourcedBehaviorTestKit[Command, Event, State] {
  import EventSourcedBehaviorTestKit.CommandResult

  private val persistenceTestKit = PersistenceTestKit(actorTestKit.system)
  private val probe = actorTestKit.createTestProbe[Any]()
  private val actor: ActorRef[Command] = actorTestKit.spawn(behavior)
  private val internalActor = actor.unsafeUpcast[Any]
  internalActor ! EventSourcedBehaviorImpl.GetPersistenceId(probe.ref)
  private val persistenceId: PersistenceId = probe.expectMessageType[PersistenceId]

  persistenceTestKit.clearByPersistenceId(persistenceId.id)

  override def runCommand(command: Command): CommandResult[Command, Event, State, Nothing] = {
    // FIXME we can expand the api of persistenceTestKit to read from storage from a seqNr instead
    val oldEvents =
      persistenceTestKit.persistedInStorage(persistenceId.id).map(_.asInstanceOf[Event])

    actor ! command

    // FIXME signals are not stashed, maybe they should (and then this awaitAssert wouldn't be needed
    val tmpStateProbe = actorTestKit.createTestProbe[State]()
    Thread.sleep(50)
    val newState = tmpStateProbe.awaitAssert {
      internalActor ! EventSourcedBehaviorImpl.GetState(tmpStateProbe.ref)
      tmpStateProbe.receiveMessage(200.millis)
    }
    tmpStateProbe.stop()

    val newEvents =
      persistenceTestKit.persistedInStorage(persistenceId.id).map(_.asInstanceOf[Event]).drop(oldEvents.size)

    CommandResult[Command, Event, State, Nothing](command, newEvents, newState, None)
  }

  override def runCommand[R](creator: ActorRef[R] => Command): CommandResult[Command, Event, State, R] = {
    val replyInbox = TestInbox[R]()
    val result = runCommand(creator(replyInbox.ref))
    val reply =
      if (replyInbox.hasMessages)
        Some(replyInbox.receiveMessage())
      else
        None

    CommandResult[Command, Event, State, R](result.command, result.events, result.state, reply)
  }
}
