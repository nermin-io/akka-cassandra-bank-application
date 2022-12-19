package me.nerminsehic.bank.actors

import akka.actor.typed.ActorRef

class PersistentBankAccount {

  // commands
  sealed trait Command

  case class CreateBankAccount(user: String, currency: String, initialBalance: Int, replyTo: ActorRef[Response]) extends Command
  case class UpdateBalance(id: String, currency: String, amount: Int, replyTo: ActorRef[Response]) extends Command
  case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command

  // events
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Int) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Int)

  // responses
  sealed trait Response
  case class BankAccountCreatedResponse(id: String) extends Response
  case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
  case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
}
