package nodescala

import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.collection.Iterator
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.mapAsScalaMap
import scala.collection.Map
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import scala.concurrent._
import ExecutionContext.Implicits.global
import scala.util.Success

/**
 * Contains utilities common to the NodeScala framework.
 */
trait NodeScala {
  import NodeScala._

  def port: Int

  def createListener(relativePath: String): Listener

  /**
   * Uses the response object to respond to the write the response back.
   *  The response should be written back in parts, and the method should
   *  occasionally check that server was not stopped, otherwise a very long
   *  response may take very long to finish.
   *
   *  @param exchange     the exchange used to write the response back
   *  @param token        the cancellation token for
   *  @param body         the response to write back
   */
  private def respond(exchange: Exchange, token: CancellationToken, response: Response): Unit = {
    try {
      while (response.hasNext && !token.isCancelled) {
        exchange.write(response.next)
      }
    } finally {
      exchange.close
    }
  }

  /**
   * A server:
   *  1) creates and starts an http listener
   *  2) creates a cancellation token (hint: use one of the `Future` companion methods)
   *  3) as long as the token is not cancelled and there is a request from the http listener
   *     asynchronously process that request using the `respond` method
   *
   * The flow for the http server is-
   *  1. Start the server with this start method
   *  2. The createListener method is invoked creates a Listener that starts a HttpServer on the port. The 
   *  relativePath will be used for creating and removing the contexts later
   *  3. An infinite running but cancellable future (main) is created and started. In this future, the promise
   *  of the next request is created
   *  4. Getting the next request promise creates the context on the listener and attaches a handler which 
   *  will be invoked when an actual request arrives causing the future to be successful. This context will be 
   *  removed once this promise is completed.
   *  5. Now the main loop can notice that the next request has really arrive and respond to the request in 
   *  an async fashion
   *  6. A new future is obtained for the next request which re-registers a new context with the listener
   *  7. A composite cancellable subscription is created for the listener and the main loop and returned by this
   *  method for clients to stop the server.
   * 
   * Note: This is a pretty broken implementation of a server. Normally the context would not be removed and 
   * added per request. The main listener would be the only loop invoking the handlers.
   * There is a small window when the removeContext is invoked and no new context has been created causing requests
   * to fail
   * 
   *  @param relativePath   a relative path on which to start listening on
   *  @param handler        a function mapping a request to a response
   *  @return               a subscription that can stop the server and all its asynchronous operations *entirely*.
   */
  def start(relativePath: String)(handler: Request => Response): Subscription = {
    val listener = createListener(relativePath)
    val listenerSub = listener.start

    val requestSub = Future.run() { cancelToken =>
      Future {
        var nextReq = listener.nextRequest
        while (cancelToken.nonCancelled) {
          if (nextReq.isCompleted) {
            nextReq onSuccess {
              case t => {
                val request = t._1
                val exchange = t._2
                val response = handler(request)
                respond(exchange, cancelToken, response)
              }
            }
            nextReq = listener.nextRequest
          }
        }
      }
    }
    Subscription.apply(listenerSub, requestSub)
  }
}

object NodeScala {

  /**
   * A request is a multimap of headers, where each header is a key-value pair of strings.
   */
  type Request = Map[String, List[String]]

  /**
   * A response consists of a potentially long string (e.g. a data file).
   *  To be able to process this string in parts, the response is encoded
   *  as an iterator over a subsequences of the response string.
   */
  type Response = Iterator[String]

  /**
   * Used to write the response to the request.
   */
  trait Exchange {
    /**
     * Writes to the output stream of the exchange.
     */
    def write(s: String): Unit

    /**
     * Communicates that the response has ended and that there
     *  will be no further writes.
     */
    def close(): Unit

    def request: Request

  }

  /**
   * Create an Exchange that is based on sun.net.httpserver.HttpExchange
   */
  object Exchange {
    def apply(exchange: HttpExchange) = new Exchange {
      val os = exchange.getResponseBody()
      exchange.sendResponseHeaders(200, 0L)

      def write(s: String) = os.write(s.getBytes)

      def close() = os.close()

      def request: Request = {
        val headers = for ((k, vs) <- exchange.getRequestHeaders) yield (k, vs.toList)
        immutable.Map() ++ headers
      }
    }
  }

  trait Listener {
    def port: Int

    def relativePath: String

    def start(): Subscription

    def createContext(handler: Exchange => Unit): Unit

    def removeContext(): Unit

    /**
     * Given a relative path:
     *  1) constructs an uncompleted promise
     *  2) installs an asynchronous request handler using `createContext`
     *     that completes the promise with a request when it arrives
     *     and then deregisters itself using `removeContext`
     *  3) returns the future with the request
     *
     *  @param relativePath    the relative path on which we want to listen to requests
     *  @return                the promise holding the pair of a request and an exchange object
     */
    def nextRequest(): Future[(Request, Exchange)] = {
      val p = Promise[(Request, Exchange)]
      createContext(exchg => {
        p.success((exchg.request, exchg))
        removeContext
      })
      p.future
    }
  }

  object Listener {

    /**
     * Create a server based on com.sun.net.httpserver
     * See http://docs.oracle.com/javase/6/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/package-summary.html
     *
     */
    class Default(val port: Int, val relativePath: String) extends Listener {
      private val server = HttpServer.create(new InetSocketAddress(port), 0)
      private val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue)
      server.setExecutor(executor)

      def start() = {
        server.start()
        new Subscription {
          def unsubscribe() = {
            server.stop(0)
            executor.shutdown()
          }
        }
      }

      def createContext(handler: Exchange => Unit) = {
        server.createContext(relativePath, new HttpHandler {
          def handle(httpxchg: HttpExchange) = handler(Exchange(httpxchg))
        })
      }

      def removeContext() = server.removeContext(relativePath)
    }
  }

  /**
   * The standard server implementation.
   */
  class Default(val port: Int) extends NodeScala {
    def createListener(relativePath: String) = new Listener.Default(port, relativePath)
  }

}
