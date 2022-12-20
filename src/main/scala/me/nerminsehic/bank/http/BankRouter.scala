package me.nerminsehic.bank.http

import akka.http.scaladsl.server.Directives._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import me.nerminsehic.bank.actors.PersistentBankAccount.{Command, Response}
import me.nerminsehic.bank.actors.PersistentBankAccount.Command._
import me.nerminsehic.bank.actors.PersistentBankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._

case class BankAccountCreationRequest(user: String, currency: String, balance: Int) {
  def toCommand(replyTo: ActorRef[Response]): Command
    = CreateBankAccount(user, currency, balance, replyTo)
}

case class BankAccountUpdateRequest(currency: String, amount: Int) {
  def toCommand(id: String, replyTo: ActorRef[Response]): Command
    = UpdateBalance(id, currency, amount, replyTo)
}

case class FailureResponse(reason: String)

class BankRouter(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  private def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))

  private def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))

  private def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

  /*
    POST /bank/
    Payload: bank account creation request as JSON
    Response:
      201 Created
      Location: /bank/uuid

    GET /bank/uuid
      Response:
        200 OK
        JSON repr of bank account details

    PUT /bank/uuid
      Payload: (current, amount) as JSON
      Response:
        - 200 OK
          Payload of new bank account as JSON
        - 404 NOT FOUND
        - TODO 400 BAD REQUEST
   */
  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          entity(as[BankAccountCreationRequest]) { request =>

            onSuccess(createBankAccount(request)) {
              case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
            }
          }
        }
      } ~
        path(Segment) { id =>
          get {
            onSuccess(getBankAccount(id)) {
              case GetBankAccountResponse(Some(account)) =>
                complete(account)

              case GetBankAccountResponse(None) =>
                complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
            }
          } ~ put {
            entity(as[BankAccountUpdateRequest]) { request =>

              onSuccess(updateBankAccount(id, request)) {
                case BankAccountBalanceUpdatedResponse(Some(account)) =>
                  complete(account)

                case BankAccountBalanceUpdatedResponse(None) =>
                  complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found"))
              }
            }
          }
        }
    }

}
