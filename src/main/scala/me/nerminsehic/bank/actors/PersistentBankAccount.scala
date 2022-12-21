package me.nerminsehic.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.util.{Failure, Success, Try}

object PersistentBankAccount {

  import Command._
  import Response._
  // command handler => message handler => persist an event
  // event handler => update state
  // state

  // commands
  sealed trait Command

  object Command {
    case class CreateBankAccount(user: String, currency: String, initialBalance: Int, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Int, replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Int) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Int)

  // responses
  sealed trait Response

  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Try[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }

  private val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) // persisted into cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))

      case UpdateBalance(_, _, amount, replyTo) =>
        val newBalance = state.balance + amount
        if(newBalance < 0) // illegal
          Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Cannot withdraw more than available"))))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(replyTo)(newState => BankAccountBalanceUpdatedResponse(Success(newState)))

      case GetBankAccount(_, replyTo) =>
        Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))

    }

  private val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}