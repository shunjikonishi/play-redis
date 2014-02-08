package flect.redis

import play.api.Logger
import com.redis.RedisClient
import com.redis.RedisClientPool
import java.net.URI
/*
import java.io.OutputStream
import com.redis.{ PubSubMessage, S, U, M, E}
import play.api.Play
import play.api.Play.current
import play.api.Logger
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Concurrent
import play.api.libs.concurrent.Akka
import akka.actor.Props
import akka.actor.Actor
import akka.actor.PoisonPill
import play.api.libs.concurrent.Execution.Implicits.defaultContext
*/


class RedisService(redisUrl: String) {
  private val (host, port, secret, pool) = {
    val uri = new URI(redisUrl)
    val host = uri.getHost
    val port = uri.getPort
    val secret = uri.getUserInfo.split(":").toList match {
      case username :: password :: Nil => Some(password)
      case _ => None
    }
    val pool = new RedisClientPool(host, port, secret=secret)
    Logger.info(s"Redis host: $host, Redis port: $port")
    (host, port, secret, pool)
  }
  
  def createClient = {
    val client = new RedisClient(host, port)
    secret.foreach(client.auth(_))
    client
  }
  
  def withClient[T](body: RedisClient => T) = {
    val client = pool.pool.borrowObject
    try {
      if (!client.connected && secret.isDefined) {
        Logger.info("Redis disconnected. Auth again.")
        secret.foreach(client.auth(_))
      }
      body(client)
    } catch {
      case e: Exception =>
        if (secret.isDefined) {
          Logger.info("Retry auth of redis")
          secret.foreach(client.auth(_))
          body(client)
        } else {
          throw e
        }
    } finally {
      pool.pool.returnObject(client)
    }
  }
  
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
    Logger.error("Subscriber error", ex)
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

