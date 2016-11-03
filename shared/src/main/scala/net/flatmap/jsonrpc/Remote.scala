package net.flatmap.jsonrpc

import akka.actor.{ActorRef, Cancellable, Props}
import akka.stream.actor._
import akka.stream._
import akka.stream.actor.ActorSubscriberMessage.{OnComplete, OnNext}
import akka.stream.scaladsl._

import scala.concurrent.{ExecutionContext, Future, Promise}
import akka.pattern.ask
import akka.util.Timeout

import scala.reflect.ClassTag


class Remote[I <: Interface] private (val interface: I,
                                      sink: ActorRef,
                                      ids: Iterator[Id])
                                     (implicit ec: ExecutionContext) {

  def call[P,R,E](rt: interface.RequestType[P,R,E])(p: P)
                 (implicit timeout: Timeout): Future[R] = {
    val response = for {
      future   <- (sink ? Request(ids.next(),rt.name,rt.paramEncoder(p)))
        .mapTo[Future[ResponseMessage]]
      response <- future
    } yield response

    response.flatMap {
      case Response.Success(_, json) =>
        rt.resultDecoder.decodeJson(json).fold({ failure =>
          val err = ResponseError(ErrorCodes.ParseError, "result could not be parsed")
          Future.failed(err)
        }, Future.successful)
      case Response.Failure(_, json) =>
        Future.failed(json)
    }
  }

  def call[P](nt: interface.NotificationType[P])(p: P) = {
    sink ! Notification(nt.name,nt.paramEncoder(p))
  }

  implicit class callableRequestType[P,R,E](rt: interface.RequestType[P,R,E]) {
    def apply(p: P)(implicit timeout: Timeout): Future[R] = call(rt)(p)
  }

  implicit class callableNotificationType[P](nt: interface.NotificationType[P]) {
    def apply(p: P): Unit = call(nt)(p)
  }

  private var closed = false

  def close()(implicit timeout: Timeout) = if (!closed)
    sink ! ResponseResolver.Cancel(timeout)
}

private object ResponseResolver {
  val props = Props(new ResponseResolver)
  case class Cancel(timeout: Timeout)
}

private class ResponseResolver extends ActorSubscriber {
  protected def requestStrategy: RequestStrategy = WatermarkRequestStrategy(16)

  private case object CancelUpstream

  implicit val dispatcher = context.dispatcher

  def receive = {
    case source: SourceQueueWithComplete[RequestMessage @unchecked] =>
      context.become(initialized(source,Map.empty))
  }

  def initialized(source: SourceQueueWithComplete[RequestMessage],
                  pending: Map[Id,Promise[ResponseMessage]]): Receive = {
    case OnNext(response: ResponseMessage) =>
      pending.get(response.id).fold {
        // We are not waiting for this reponse...
        // TODO: Do something
      } { promise =>
        context.become(initialized(source, pending - response.id))
        promise.success(response)
      }
    case OnComplete =>
      pending.foreach {
        case (id,promise) => promise.failure(
          ResponseError(ErrorCodes.InternalError,"closed source")
        )
      }
      context.stop(self)
    case r @ Request(id, _, _) =>
      val promise = Promise[ResponseMessage]
      context.become(initialized(source, pending.updated(id, promise)))
      source.offer(r)
      sender ! promise.future
    case n: Notification =>
      source.offer(n)
    case ResponseResolver.Cancel(timeout) =>
      source.complete()
      val t = context.system.scheduler.scheduleOnce(timeout.duration,self,CancelUpstream)
      context.become(shuttingDown(sender, pending, t))
  }

  def shuttingDown(sender: ActorRef, pending: Map[Id,Promise[ResponseMessage]], timeout: Cancellable): Receive = {
    if (pending.isEmpty) {
      timeout.cancel()
      cancel()
    }

    {
      case OnNext(response: ResponseMessage) =>
        pending.get(response.id).fold {
          // We are not waiting for this reponse...
          // TODO: Do something
        } { promise =>
          context.become(shuttingDown(sender, pending - response.id, timeout))
          promise.success(response)
        }
      case OnComplete =>
        pending.foreach {
          case (id,promise) => promise.failure(
            ResponseError(ErrorCodes.InternalError,"closed source")
          )
        }
        timeout.cancel()
        context.stop(self)
      case CancelUpstream =>
        timeout.cancel()
        cancel()
    }
  }
}

object Remote {
  def apply[I <: Interface](interface: I, idProvider: Iterator[Id])
                           (implicit ec: ExecutionContext): Flow[ResponseMessage,RequestMessage,Remote[I]] = {
    val sink = Sink.actorSubscriber(ResponseResolver.props)
    val source = Source.queue[RequestMessage](bufferSize = 16, OverflowStrategy.backpressure)
    Flow.fromSinkAndSourceMat(sink,source) {
      (sink, source) =>
        sink ! source
        new Remote[I](interface, sink, idProvider)
    }
  }
}