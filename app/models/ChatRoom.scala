package models

import akka.actor.ActorRef
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.duration.DurationInt
import scala.concurrent.Future

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
  
  private val closer: Closer = new Closer(this.close)
  def active = !closer.closed
  
  def connect = closer.inc
  def disconnect = closer.desc
  
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
      Logger.info("test: " + Thread.currentThread.getName)
      channel.send(msg)
    }.map { _ =>
      redis.withClient(_.lrem(member_key, 1, username))
      channel.send(message("quit", username, "has left the room"))
      ChatRoom.disconnect(name)
    }
  }
    
  def join(username: String): (Iteratee[String,_], Enumerator[String]) = {
    Logger.info("Join: " + username)
    connect
    
    val members = getMembers
    if (username == "Robot" || members.contains(username)) {
      disconnect
      ChatRoom.error("This username is already used")
    } else {
      redis.withClient(_.rpush(member_key, username))
      val in = createIteratee(username)
      (in, channel.out)
    }
  }
  
  def reconnect(username: String): (Iteratee[String,_], Enumerator[String]) = {
    Logger.info("Reconnect: " + username)
    connect
    
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
  
  val schedule = Akka.system.scheduler.schedule(25 seconds, 25 seconds) {
    if (active) {
      channel.send(message("talk", "Robot", "I'm still alive"))
    }
  }
  
  def close = {
    channel.close
    schedule.cancel
  }
}

object ChatRoom {
  
  sealed class Msg
  case class Join(room: String, username: String)
  case class Reconnect(room: String, username: String)
  case class Disconnect(room: String)
  
  class MyActor extends Actor {
    def receive = {
      case Join(room, username) => 
        val ret = try {
          get(room).join(username)
        } catch {
          case e: Exception =>
            e.printStackTrace
            error(e.getMessage)
        }
        sender ! ret
      case Reconnect(room, username) => 
        val ret = try {
          get(room).reconnect(username)
        } catch {
          case e: Exception =>
            e.printStackTrace
            error(e.getMessage)
        }
        sender ! ret
      case Disconnect(room) =>
        get(room).disconnect
        sender ! true
    }
  }
  
  def join(room: String, username: String): Future[(Iteratee[String,_], Enumerator[String])] = {
    (actor ? Join(room, username)).asInstanceOf[Future[(Iteratee[String,_], Enumerator[String])]]
  }
  
  def reconnect(room: String, username: String): Future[(Iteratee[String,_], Enumerator[String])] = {
    (actor ? Reconnect(room, username)).asInstanceOf[Future[(Iteratee[String,_], Enumerator[String])]]
  }
  
  def disconnect(room: String) = {
    actor ! Disconnect(room)
  }
  
  var rooms = Map.empty[String, ChatRoom]
  
  private def get(name: String): ChatRoom = {
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
  
  implicit val timeout = Timeout(5 seconds)
  
  val actor = Akka.system.actorOf(Props(new MyActor()))
  
  sys.ShutdownHookThread {
    println("!!!! ShutdownHook !!!!")
  }
}

class Closer(body: => Any) {
  private var counter = 0
  private var active = true
  
  def closed = !active
  def inc = synchronized {
println("Closer#inc")
    if (active) counter += 1
  }
  def desc = synchronized {
println("Closer#desc")
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
