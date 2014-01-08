package models

import java.io.OutputStream
import com.redis.RedisClient
import com.redis.RedisClientPool
import com.redis.{ PubSubMessage, S, U, M, E}
import play.api.Play
import play.api.Play.current
import play.api.Logger
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Concurrent
import play.api.libs.concurrent.Akka
import akka.actor.Props
import akka.actor.Actor

import play.api.libs.concurrent.Execution.Implicits.defaultContext

class RedisService(redisUrl: String) {
  private val (host, port, pool) = {
    val uri = new java.net.URI(redisUrl)
    val host = uri.getHost
    val port = uri.getPort
    val secret = uri.getUserInfo.split(":")(1)
    val pool = new RedisClientPool(host, port, secret=Some(secret))
    Logger.info(s"Redis host: $host, Redis port: $port")
    (host, port, pool)
  }
  
  def withClient[T](body: RedisClient => T) = pool.withClient(body)
  def borrowClient = pool.pool.borrowObject
  def returnClient(client: RedisClient) = pool.pool.returnObject(client)
  
  def close = pool.close
  
  def createPubSub(channel: String,
    send: String => String = null,
    receive: String => String = null,
    disconnect: => String = null,
    exception: Throwable => Unit = defaultException,
    subscribe: (String, Int) => Unit = defaultSubscribe,
    unsubscribe: (String, Int) => Unit = defaultUnsubscribe
  ) = new PubSubChannel(this, channel, 
    Option(send), Option(receive), Option(() => disconnect), 
    Option(exception), Option(subscribe), Option(unsubscribe)
  )
  
  private def defaultException: Throwable => Unit = { ex =>
    Logger.error("Fatal error caused consumer dead. Please init new consumer reconnecting to master or connect to backup", ex)
  }
  
  private def defaultSubscribe: (String, Int) => Unit = { (c, n) =>
    Logger.info("subscribed to " + c + " and count = " + n)
  }
  
  private def defaultUnsubscribe: (String, Int) => Unit = { (c, n) =>
    Logger.info("unsubscribed from " + c + " and count = " + n)
  }
}

object RedisService {
  def apply(uri: String) = new RedisService(uri)
}

class PubSubChannel(redis: RedisService, channel: String,
  send: Option[String => String] = None,
  receive: Option[String => String] = None,
  disconnect: Option[() => String] = None,
  exception: Option[Throwable => Unit] = None,
  subscribe: Option[(String, Int) => Unit] = None,
  unsubscribe: Option[(String, Int) => Unit] = None
  ) {
  
  private val (msgEnumerator, msgChannel) = Concurrent.broadcast[String]
  private val pub = Akka.system.actorOf(Props(new Publisher()))
  private val sub = {
    val client = redis.borrowClient
    client.subscribe(channel)(callback)
    client
  }
  
  lazy val in = Iteratee.foreach[String] { msg =>
    val str = send.map(_(msg)).getOrElse(msg)
    send(str)
  }.map { _ =>
    disconnect.foreach { f =>
      val msg = f()
      send(msg)
    }
    close
  }
  
  lazy val out = msgEnumerator
  
  private def callback(pubsub: PubSubMessage): Unit = pubsub match {
    case E(ex) => 
      exception.foreach(_(ex))
    case S(channel, no) => 
      subscribe.foreach(_(channel, no))
    case U(channel, no) => 
      unsubscribe.foreach(_(channel, no))
      if (no == 0) {
        sub.pubSub = false
      }
    case M(channel, msg) => 
      Logger.debug("receive: " + msg)
      val str = receive.map(_(msg)).getOrElse(msg)
      msgChannel.push(str)
  }
  
  def send(msg: String) = {
    Logger.debug("send: " + msg)
    pub ! msg
  }
  
  def close = {
    Logger.info("close: " + channel)
    sub.unsubscribe(channel)
    redis.returnClient(sub)
  }
  
  class Publisher extends Actor {
    def receive = {
      case msg: String =>
        redis.withClient { _.publish(channel, msg)}
        sender ! true
    }
  }
}

