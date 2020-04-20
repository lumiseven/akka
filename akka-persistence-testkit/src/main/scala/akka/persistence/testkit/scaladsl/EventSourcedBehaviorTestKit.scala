/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import scala.collection.immutable

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.testkit.internal.EventSourcedBehaviorTestKitImpl
import akka.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.CommandResult

object EventSourcedBehaviorTestKit {

  def apply[Command, Event, State](
      system: ActorSystem[_],
      behavior: Behavior[Command]): EventSourcedBehaviorTestKit[Command, Event, State] =
    new EventSourcedBehaviorTestKitImpl(ActorTestKit(system), behavior)

  final case class CommandResult[Command, Event, State, Reply](
      command: Command,
      events: immutable.Seq[Event],
      state: State,
      reply: Option[Reply])
}

trait EventSourcedBehaviorTestKit[Command, Event, State] {
  def runCommand(command: Command): CommandResult[Command, Event, State, Nothing]

  def runCommand[R](creator: ActorRef[R] => Command): CommandResult[Command, Event, State, R]
}
