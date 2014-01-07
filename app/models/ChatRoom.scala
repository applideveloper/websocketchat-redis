package models

import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.duration.DurationInt

import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.json.JsValue
import play.api.libs.json.JsObject
import play.api.libs.json.JsString
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.Done
import play.api.libs.iteratee.Input
import play.api.libs.iteratee.Concurrent
import play.api.libs.concurrent.Akka

import akka.util.Timeout
import akka.pattern.ask

import play.api.Play
import play.api.Play.current
import play.api.libs.concurrent.Execution.Implicits.defaultContext

object MyRedisService extends RedisService(Play.configuration.getString("redis.uri").get)

class ChatRoom(name: String, redis: RedisService) {
  var members = Set.empty[String]
  val channel = redis.createPubSub(name, receive=this.receive)
  
  private var closed = false
  def active = !closed
  
  case class Message(kind: String, user: String, message: String, members: List[String])
  implicit val messageReads = Json.reads[Message]
  implicit val messageWrites = Json.writes[Message]
  
  private def message(kind: String, user: String, message: String): String = {
    val msg = new Message(kind, user, message, "Robot" :: members.toList)
    Json.stringify(messageWrites.writes(msg))
  }
    
  def join(username: String): (Iteratee[String,_], Enumerator[String]) = {
    if (username == "Robot" || members.contains(username)) {
      val iteratee = Done[String,Unit]((),Input.EOF)
      val enumerator =  Enumerator[String](JsObject(Seq("error" -> JsString("This username is already used"))).toString).andThen(Enumerator.enumInput(Input.EOF))
      (iteratee,enumerator)
    } else {
      redis.rpush(name + "-members", username)
      members = members + username
      val msg = message("join", username, "has entered the room")
      channel.send(msg)
      val in = Iteratee.foreach[String] { msg =>
        val json = Json.parse(msg)
        channel.send(message("talk", username, (json \ "text").as[String]))
      }.map { _ =>
        redis.lrem(name + "-members", 1, username)
        channel.send(message("quit", username, "has left the room"))
      }
      (in, channel.out)
    }
  }
  
  def receive(msg: String): String = {
    val obj = Json.parse(msg)
    val kind = (obj \ "kind").as[String]
    val user = (obj \ "user").as[String]
    val str = (obj \ "message").as[String]
    kind match {
      case s if (s == "join" || s == "quit") =>
        redis.lrange(name + "-members", 0, -1).foreach { l =>
          println("lrange: " + l)
          members = l.filter(_.isDefined).map(_.get).toSet
        }
        message(kind, user, str)
      case _ =>
        msg
    }
  }
  
  Akka.system.scheduler.schedule(25 seconds, 25 seconds) {
    if (active) {
      channel.send(message("talk", "Robot", "I'm still alive"))
    }
  }
}

object ChatRoom {
  
  var rooms = Map.empty[String, ChatRoom]
  
  def get(name: String): ChatRoom = {
    val room = rooms.get(name).filter(_.active)
    room match {
      case Some(x) => x
      case None =>
        val ret = new ChatRoom(name, MyRedisService)
        rooms = rooms + (name -> ret)
        ret
    }
  }
}
