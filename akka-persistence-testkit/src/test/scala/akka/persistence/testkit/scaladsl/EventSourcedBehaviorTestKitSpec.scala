/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.CborSerializable
import akka.util.unused
import org.scalatest.wordspec.AnyWordSpecLike

object EventSourcedBehaviorTestKitSpec {

  object TestCounter {
    sealed trait Command extends CborSerializable
    case object Increment extends Command
    final case class IncrementWithConfirmation(replyTo: ActorRef[Done]) extends Command
    final case class GetValue(replyTo: ActorRef[State]) extends Command

    sealed trait Event extends CborSerializable
    final case class Incremented(delta: Int) extends Event

    final case class State(value: Int, history: Vector[Int]) extends CborSerializable

    def apply(persistenceId: PersistenceId): Behavior[Command] =
      Behaviors.setup(ctx => counter(ctx, persistenceId))

    private def counter(
        @unused ctx: ActorContext[Command],
        persistenceId: PersistenceId): EventSourcedBehavior[Command, Event, State] = {
      EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
        persistenceId,
        emptyState = State(0, Vector.empty),
        commandHandler = (state, command) =>
          command match {
            case Increment =>
              Effect.persist(Incremented(1)).thenNoReply()

            case IncrementWithConfirmation(replyTo) =>
              Effect.persist(Incremented(1)).thenReply(replyTo)(_ => Done)

            case GetValue(replyTo) =>
              Effect.reply(replyTo)(state)

          },
        eventHandler = (state, evt) =>
          evt match {
            case Incremented(delta) =>
              State(state.value + delta, state.history :+ state.value)
          })
    }
  }
}

class EventSourcedBehaviorTestKitSpec
    extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config)
    with AnyWordSpecLike
    with LogCapturing {
  import EventSourcedBehaviorTestKitSpec._

  private val persistenceId = PersistenceId.ofUniqueId("test")
  private val behavior = TestCounter(persistenceId)

  "EventSourcedBehaviorTestKit" must {

    "run commands" in {
      val eventSourcedTestKit =
        EventSourcedBehaviorTestKit[TestCounter.Command, TestCounter.Event, TestCounter.State](system, behavior)

      val result1 = eventSourcedTestKit.runCommand(TestCounter.Increment)
      result1.events should ===(List(TestCounter.Incremented(1)))
      result1.state should ===(TestCounter.State(1, Vector(0)))
      result1.reply should ===(None)

      val result2 = eventSourcedTestKit.runCommand(TestCounter.Increment)
      result2.events should ===(List(TestCounter.Incremented(1)))
      result2.state should ===(TestCounter.State(2, Vector(0, 1)))
      result2.reply should ===(None)
    }

    "run commands with reply" in {
      val eventSourcedTestKit =
        EventSourcedBehaviorTestKit[TestCounter.Command, TestCounter.Event, TestCounter.State](system, behavior)

      val result1 = eventSourcedTestKit.runCommand(TestCounter.IncrementWithConfirmation(_))
      result1.events should ===(List(TestCounter.Incremented(1)))
      result1.state should ===(TestCounter.State(1, Vector(0)))
      result1.reply should ===(Some(Done))

      val result2 = eventSourcedTestKit.runCommand(TestCounter.IncrementWithConfirmation(_))
      result2.events should ===(List(TestCounter.Incremented(1)))
      result2.state should ===(TestCounter.State(2, Vector(0, 1)))
      result2.reply should ===(Some(Done))
    }
  }

}
