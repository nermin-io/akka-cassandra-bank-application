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
import Validation._
import akka.http.scaladsl.server.Route
import cats.data.Validated.{Invalid, Valid}
import cats.implicits._

import scala.util.{Failure, Success}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreationRequest] = new Validator[BankAccountCreationRequest] {
    override def validate(request: BankAccountCreationRequest): ValidationResult[BankAccountCreationRequest] = {
      val userValidation = validateRequired(request.user, "user")
      val currencyValidation = validateRequired(request.currency, "currency")
      val balanceValidation = validateMinimum(request.balance, 0, "balance")
        .combine(validateMinimumAbs(request.balance, 0, "balance"))

      (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreationRequest.apply)
    }
  }
}

case class BankAccountCreationRequest(user: String, currency: String, balance: Int) {
  def toCommand(replyTo: ActorRef[Response]): Command
  = CreateBankAccount(user, currency, balance, replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = new Validator[BankAccountUpdateRequest] {
    override def validate(request: BankAccountUpdateRequest): ValidationResult[BankAccountUpdateRequest] = {
      val currencyValidation = validateRequired(request.currency, "currency")
      val amountValidation = validateMinimumAbs(request.amount, 1, "amount")

      (currencyValidation, amountValidation).mapN(BankAccountUpdateRequest.apply)
    }
  }
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

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(
          StatusCodes.BadRequest,
          FailureResponse(failures.toList.map(_.errorMessage).mkString(", "))
        )
    }

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
        - 400 BAD REQUEST
   */
  val routes =
    pathPrefix("bank") {
      pathEndOrSingleSlash {
        post {
          entity(as[BankAccountCreationRequest]) { request =>

            // validation
            validateRequest(request) {
              onSuccess(createBankAccount(request)) {
                case BankAccountCreatedResponse(id) =>
                  respondWithHeader(Location(s"/bank/$id")) {
                    complete(StatusCodes.Created)
                  }
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

              // validation
              validateRequest(request) {
                onSuccess(updateBankAccount(id, request)) {
                  case BankAccountBalanceUpdatedResponse(Success(account)) =>
                    complete(account)

                  case BankAccountBalanceUpdatedResponse(Failure(ex)) =>
                    complete(StatusCodes.BadRequest, FailureResponse(s"${ex.getMessage}"))
                }
              }
            }
          }
        }
    }

}
