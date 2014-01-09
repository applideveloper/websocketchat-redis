package controllers

import play.api._
import play.api.mvc._

import play.api.libs.json._
import play.api.libs.iteratee._

import models._

import akka.actor._
import scala.concurrent.duration._

object Application extends Controller {
  
  /**
   * Just display the home page.
   */
  def index = Action { implicit request =>
    Ok(views.html.index())
  }
  
  /**
   * Display the chat room page.
   */
  def chatRoom(room: String, username: Option[String]) = Action { implicit request =>
    username.filterNot(_.isEmpty).map { username =>
      Ok(views.html.chatRoom(room, username))
    }.getOrElse {
      Redirect(routes.Application.index).flashing(
        "error" -> "Please choose a valid username."
      )
    }
  }
  
  /**
   * Handles the chat websocket.
   */
  def chat(room: String, username: String) = WebSocket.async[String] { request  =>
    ChatRoom.join(room, username)
  }
  
  def reconnect(room: String, username: String) = WebSocket.async[String] { request  =>
    ChatRoom.reconnect(room, username)
  }
  
  def gc = Action {
    System.gc
    Ok("OK")
  }
}
