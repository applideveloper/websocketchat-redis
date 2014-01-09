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
import play.api.libs.json.JsSuccess
import play.api.libs.json.JsError
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
  val channel = redis.createPubSub(name, receive=this.receive)
  
  private val member_key = name + "-members"
  
  private val closer = new Closer(channel.close)
  def active = !closer.closed
  
  case class Message(kind: String, user: String, message: String, members: Option[List[String]])
  implicit val messageFormat = Json.format[Message]
  
  private def message(kind: String, user: String, message: String, members: Option[List[String]] = None): String = {
    val msg = new Message(kind, user, message, members)
    Json.toJson(msg).toString
  }
  
  private def getMembers = redis.withClient {
    _.lrange(member_key, 0, -1).map(_.flatten).getOrElse(Nil)
  }
  
  private def createIteratee(username: String): Iteratee[String, _] = {
    Iteratee.foreach[String] { msg =>
      channel.send(msg)
    }.map { _ =>
      redis.withClient(_.lrem(member_key, 1, username))
      channel.send(message("quit", username, "has left the room"))
      closer.desc
    }
  }
    
  def join(username: String): (Iteratee[String,_], Enumerator[String]) = {
    Logger.info("Join: " + username)
    closer.inc
    
    val members = getMembers
    if (username == "Robot" || members.contains(username)) {
      closer.desc
      ChatRoom.error("This username is already used")
    } else {
      redis.withClient(_.rpush(member_key, username))
      val in = createIteratee(username)
      (in, channel.out)
    }
  }
  
  def reconnect(username: String): (Iteratee[String,_], Enumerator[String]) = {
    Logger.info("Reconnect: " + username)
    closer.inc
    
    val in = createIteratee(username)
    val members = getMembers
    if (!members.contains(username)) {
      redis.withClient(_.rpush(member_key, username))
    }
    (in, channel.out)
  }
  
  def receive(msg: String): String = {
    Json.fromJson[Message](Json.parse(msg)) match {
      case JsSuccess(obj, _) =>
        obj.kind match {
          case s if (s == "join" || s == "quit") =>
            val members = getMembers
            message(obj.kind, obj.user, obj.message, Some("Robot" :: members))
          case _ =>
            msg
        }
      case JsError(errors) =>
        Logger.error("Message error: " + errors)
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
  
  def error(msg: String): (Iteratee[String,_], Enumerator[String]) = {
    Logger.info("Can not connect chat room: " + msg)
    val in = Done[String,Unit]((),Input.EOF)
    val out =  Enumerator[String](JsObject(Seq("error" -> JsString(msg))).toString).andThen(Enumerator.enumInput(Input.EOF))
    (in, out)
  }
  
  sys.ShutdownHookThread {
    println("!!!! ShutdownHook !!!!")
  }
}

class Closer(body: => Any) {
  private var counter = 0
  private var active = true
  
  def closed = !active
  def inc = synchronized {
    if (active) counter += 1
  }
  def desc = synchronized {
    if (active) {
      if (counter == 0) {
        throw new IllegalStateException("Counter doesn't incremented.")
      }
      counter -= 1
      if (counter == 0) {
        active = false
        body
      }
    }
  }
}
