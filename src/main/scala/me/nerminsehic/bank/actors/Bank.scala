package me.nerminsehic.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

object Bank {

  import PersistentBankAccount.Command._
  import PersistentBankAccount.Command

  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  case class State(accounts: Map[String, ActorRef[Command]])

  val commandHandler: (State, Command) => Effect[Event, State] = ???

  val eventHandler: (State, Event) => State = ???

  def apply(): Behavior[Command] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
