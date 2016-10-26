package net.flatmap.jsonrpc

import akka.NotUsed
import akka.actor._
import akka.stream._
import akka.stream.scaladsl._
import io.circe._
import sun.reflect.generics.reflectiveObjects.NotImplementedException

import scala.collection.GenTraversableOnce
import scala.concurrent._
import scala.language.experimental.macros
import scala.reflect.macros.blackbox.Context
import scala.util.Try
import scala.util.control.NonFatal

class RPCInterfaceMacros(val c: Context) {
  import c.universe._

  def abstractMethodsOf(t: c.Type) = {
    val methods = for {
      decl <- t.members if decl.info != null && decl.isMethod && decl.isAbstract
    } yield decl.asMethod
    methods.groupBy(_.name.toString).foreach { case (name,ms) =>
      if (ms.size > 1) {
        ms.foreach(m => c.error(m.pos, "json rpc methods may not be overloaded"))
        c.abort(c.enclosingPosition, s"json rpc interface may not contain overloaded methods ($name)")
      }
    }
    methods
  }

  def inferEncoder(t: Type) = {
    val etype = appliedType(typeOf[Encoder[_]].typeConstructor, t)
    val encoder = c.inferImplicitValue(etype)
    if (encoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find implicit io.circe.Encoder[$t]")
    encoder
  }

  def inferDecoder(t: Type) = {
    val etype = appliedType(typeOf[Decoder[_]].typeConstructor, t)
    val decoder = c.inferImplicitValue(etype)
    if (decoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find implicit io.circe.Decoder[$t]")
    decoder
  }

  def checkValid(m: MethodSymbol) = {
    if (!m.typeParams.isEmpty)
      c.abort(m.pos, "json rpc methods may not use type parameters")
    if (!(m.returnType =:= c.typeOf[Unit] || m.returnType <:< c.typeOf[Future[Any]]))
      c.abort(m.pos, "json rpc methods can either return Unit or Future[T]")
  }

  def rpcNameOf(m: MethodSymbol) =
    m.annotations.map(_.tree).collectFirst {
      case tree@Apply(annon,List(Literal(Constant(name: String))))
        if tree.tpe =:= c.typeOf[JsonRPC.Named] => name
    } getOrElse m.name.toString

  def rpcNamespaceOf(m: MethodSymbol) =
    m.annotations.map(_.tree).collectFirst {
      case tree@Apply(annon,List(Literal(Constant(name: String))))
        if tree.tpe =:= c.typeOf[JsonRPC.Namespace] => name
    }

  def isSingleParam(m: MethodSymbol) =
    m.annotations.map(_.tree).exists { tree =>
      tree.tpe =:= c.typeOf[JsonRPC.SpreadParam]
    }

  def localCases(select: Tree, prefix: String = "")(m: MethodSymbol): Seq[CaseDef] =
    rpcNamespaceOf(m).fold[Seq[CaseDef]] {
      val name = prefix + rpcNameOf(m)
      checkValid(m)

      val paramss = m.paramLists.map(_.map({ x =>
        val n = c.freshName(x.name.toTermName)
        val t = x.typeSignature
        (x.pos, x.name.toString, n, t)
      }))

      val argMatch = paramss.flatten.map(_._2)
      val argMatchNamed = argMatch.map(x => pq"${x.toString} -> $x")

      if (isSingleParam(m)) {
        if (argMatch.length != 1)
          c.abort(m.pos, "Methods annotated with JsonRPC.SpreadParam must take exactly one parameter")
        val decoder = inferDecoder(paramss.head.head._4)
        val param = c.freshName[TermName]("param")
        val decodedParam = q"$decoder.decodeJson($param.json).toTry.get"
        if (m.returnType =:= c.typeOf[Unit]) {
          Seq[CaseDef](cq"net.flatmap.jsonrpc.Notification($name,$param) => $select.${m.name}($decodedParam); None")
        } else {
          val id = c.freshName[TermName]("id")
          val rt = m.returnType.typeArgs.head
          val encoder = inferEncoder(rt)
          val res = q"Some($select.${m.name}($decodedParam).map((x) => net.flatmap.jsonrpc.Response.Success($id,$encoder(x))))"
          Seq[CaseDef](cq"net.flatmap.jsonrpc.Request($id,$name,$param) => $res")
        }
      } else {

        val args = c.freshName[TermName]("args")

        val paramsDecodedNamed = paramss.map(_.map {
          case (pos, xn, n, t) =>
            val decoder = inferDecoder(t)
            if (t <:< typeOf[Option[Any]])
              q"$args.get($xn).map($decoder.decodeJson(_).toTry.get).getOrElse(None)"
            else
              q"$decoder.decodeJson($args($xn)).toTry.get"
        })

        var i = -1
        val paramsDecodedIndexed = paramss.map(_.map {
          case (pos, xn, n, t) =>
            i += 1
            val decoder = inferDecoder(t)
            if (t <:< typeOf[Option[Any]])
              q"$args.lift($i).map($decoder.decodeJson(_).toTry.get).getOrElse(None)"
            else
              q"$decoder.decodeJson($args($i)).toTry.get"
        })

        if (m.returnType =:= c.typeOf[Unit]) {
          if (argMatch.isEmpty)
            Seq[CaseDef](cq"net.flatmap.jsonrpc.Notification($name,net.flatmap.jsonrpc.NoParameters) => $select.${m.name}(...$paramsDecodedNamed); None")
          else
            Seq[CaseDef](
              cq"net.flatmap.jsonrpc.Notification($name, net.flatmap.jsonrpc.NamedParameters($args))      => $select.${m.name}(...$paramsDecodedNamed); None",
              cq"net.flatmap.jsonrpc.Notification($name, net.flatmap.jsonrpc.PositionedParameters($args)) => $select.${m.name}(...$paramsDecodedIndexed); None")
        } else {
          val id = c.freshName[TermName]("id")
          val rt = m.returnType.typeArgs.head
          val encoder = inferEncoder(rt)
          if (argMatch.isEmpty) {
            val res = q"Some($select.${m.name}(...$paramsDecodedNamed).map((x) => net.flatmap.jsonrpc.Response.Success($id,$encoder(x))))"
            Seq[CaseDef](cq"net.flatmap.jsonrpc.Request($id, $name,net.flatmap.jsonrpc.NoParameters) => $res")
          } else Seq[CaseDef]({
            val res = q"Some($select.${m.name}(...$paramsDecodedNamed).map((x) => net.flatmap.jsonrpc.Response.Success($id,$encoder(x))))"
            cq"net.flatmap.jsonrpc.Request($id, $name, net.flatmap.jsonrpc.NamedParameters($args)) => $res"
          }, {
            val res = q"Some($select.${m.name}(...$paramsDecodedIndexed).map((x) => net.flatmap.jsonrpc.Response.Success($id,$encoder(x))))"
            cq"net.flatmap.jsonrpc.Request($id, $name, net.flatmap.jsonrpc.PositionedParameters($args)) => $res"
          })
        }
      }
    } { case namespace =>
      val t = m.returnType
      abstractMethodsOf(t).flatMap(localCases(q"$select.${m.name}",prefix + namespace)).toSeq
    }

  def localImpl[L: c.WeakTypeTag] = {
    import c.universe._
    val t = c.weakTypeOf[L]

    val impl = c.freshName[TermName]("impl")

    val cases = abstractMethodsOf(t).flatMap(localCases(q"$impl"))

    q"""net.flatmap.jsonrpc.Local.buildFlow(($impl: $t) => {
          case ..$cases
        })"""
  }

  def remoteImpls(sendNotification: TermName, sendRequest: TermName, prefix: String = "")(m: MethodSymbol): c.Tree =
    rpcNamespaceOf(m).fold {
      val name = prefix + rpcNameOf(m)
      checkValid(m)

      val paramss = m.paramLists.map(_.map({ x =>
        val n = x.name.toTermName
        val t = x.typeSignature
        (x.pos, n, t)
      }))

      val paramdecls = paramss.map(_.map({ case (pos, n, t) => q"$n: $t" }))

      val args = paramss.flatten.map {
        case (pos, n, t) =>
          val s = n.toString
          val encoder = inferEncoder(t)
          q"$s -> $encoder($n)"
      }

      val params =
        if (isSingleParam(m)) {
          if (args.size != 1)
            c.abort(m.pos, "Methods annotated with JsonRPC.SpreadParam must take exactly one parameter")
          else {
            q"net.flatmap.jsonrpc.NamedParameters(${args.head}._2.asObject.get.toMap)"
          }
        } else if (args.isEmpty) q"net.flatmap.jsonrpc.NoParameters"
        else q"net.flatmap.jsonrpc.NamedParameters(scala.collection.immutable.Map(..$args))"

      val body = if (m.returnType =:= c.typeOf[Unit]) {
        val msg = q"net.flatmap.jsonrpc.Notification($name, $params)"
        q"$sendNotification($msg)"
      } else {
        val t = m.returnType.typeArgs.head
        val decoder = inferDecoder(t)
        val msg = q"net.flatmap.jsonrpc.Request(net.flatmap.jsonrpc.Id.Null, $name, $params)"
        q"$sendRequest($msg).map($decoder.decodeJson).map(_.toTry.get)"
      }

      val returnType = m.returnType
      q"override def ${m.name}(...$paramdecls) = $body"
    } { case namespace =>
      val t = m.returnType
      val paramss = m.paramLists.map(_.map({ x =>
        val n = x.name.toTermName
        val t = x.typeSignature
        (x.pos, n, t)
      }))
      val paramdecls = paramss.map(_.map({ case (pos, n, t) => q"$n: $t" }))
      val impls = abstractMethodsOf(t).map(remoteImpls(sendNotification,sendRequest,prefix + namespace))
      q"override def ${m.name}(...$paramdecls) = new $t { ..$impls }"
    }

  def remoteImpl[R: c.WeakTypeTag](ids: c.Expr[Iterator[Id]]) = {
    import c.universe._
    val t = c.weakTypeOf[R]
    val sendNotification = c.freshName[TermName]("sendNotification")
    val sendRequest = c.freshName[TermName]("sendRequest")
    val close = c.freshName[TermName]("close")

    val impls = abstractMethodsOf(t).map(remoteImpls(sendNotification,sendRequest))

    q"""net.flatmap.jsonrpc.Remote.buildFlow($ids, {
          case ($sendRequest,$sendNotification,$close) => new $t with net.flatmap.jsonrpc.RemoteConnection {
            ..$impls
            override def close() = $close()
        }
        })"""
  }
}

object Local {
  def buildFlow[L](f: L => PartialFunction[RequestMessage, Option[Future[Response]]]) = {
    def handler(l: L) = f(l).orElse[RequestMessage,Option[Future[Response]]] {
      case Request(id,method,_) => Some(Future.successful(
        Response.Failure(id,ResponseError(ErrorCodes.MethodNotFound,s"method not found: $method", None))))
      case Notification(method,_) => Some(Future.successful(
        Response.Failure(Id.Null,ResponseError(ErrorCodes.MethodNotFound,s"method not found: $method", None))))
    }

    def recovery(handler: RequestMessage => Option[Future[Response]]): RequestMessage => Option[Future[Response]] = {
      case r: RequestMessage =>
        val id = r match {
          case Request(id,_,_) => id
          case _ => Id.Null
        }
        Try(handler(r)).recover {
          case r: ResponseError => Some(Future.successful(Response.Failure(id,r)))
          case e: NotImplementedException => Some(Future.successful(
            Response.Failure(id,ResponseError(ErrorCodes.MethodNotFound,s"method not implemented: ${r.method}"))))
          case _: NotImplementedError => Some(Future.successful(
            Response.Failure(id,ResponseError(ErrorCodes.MethodNotFound,s"method not implemented: ${r.method}"))))
          case n: NoSuchElementException => Some(Future.successful(
            Response.Failure(id,ResponseError(ErrorCodes.InvalidParams,s"invalid parameters: ${n.getMessage}"))))
          case NonFatal(e) => Some(Future.successful(
            Response.Failure(id,ResponseError(ErrorCodes.serverErrorStart,s"server error: $e", None))
          ))
        }.get
    }

    val src = Source.maybe[L].flatMapConcat(l => Source.repeat(recovery(handler(l))))
    Flow[RequestMessage].zipWithMat(src)((msg,f) => f(msg))(Keep.right).collect {
      case Some(json) => json
    }.flatMapConcat(Source.fromFuture)
  }

  def apply[L]: Flow[RequestMessage, Response, Promise[Option[L]]] =
    macro RPCInterfaceMacros.localImpl[L]
}

trait RemoteConnection {
  def close()
}

object Remote {
  def buildFlow[R](
    ids: Iterator[Id],
    derive: (Request => Future[Json], Notification => Unit, () => Unit) => R)
                  (implicit ec: ExecutionContext): Flow[Response, RequestMessage,R] = {
    val requests = Source.actorRef[RequestMessage](bufferSize = 1024, OverflowStrategy.fail)

    val idProvider = Flow[RequestMessage].scan((ids,Option.empty[RequestMessage]))({
      case ((ids,_),ResolveableRequest(method,params,promise,None)) => (ids, Some(ResolveableRequest(method,params,promise,Some(ids.next()))))
      case ((ids,_),other) => (ids,Some(other))
    }).collect { case (id,Some(x)) => x }

    val idRequests = requests.viaMat(idProvider)(Keep.left)

    val reqCycle =
      Flow[Message].scan(Map.empty[Id,Promise[Json]],Option.empty[RequestMessage])({
        case ((pending,_),ResolveableRequest(method,params,promise,Some(id))) =>
          (pending + (id -> promise), Some(Request(id,method,params)))
        case ((pending,_),other: RequestMessage) =>
          (pending, Some(other))
        case ((pending,_),Response.Success(id,result)) =>
          pending.get(id).fold {
            // TODO: not waiting for this response
          } { p =>
            p.success(result)
          }
          (pending - id, None)
        case ((pending,_),Response.Failure(id,result)) =>
          pending.get(id).fold {
            // TODO: not waiting for this response
          } { p =>
            p.failure(result)
          }
          (pending - id, None)
      }).map(_._2).collect[RequestMessage] { case Some(x) => x }

    val flow = Flow[Response]
      .watchTermination()(Keep.right)
      .mergeMat(idRequests)(Keep.both)
      .viaMat(reqCycle)(Keep.left)

    flow.mapMaterializedValue { case (f,ref) =>
      derive({
        case Request(id,method,params) =>
          val p = Promise[Json]
          ref ! ResolveableRequest(method,params,p)
          p.future
      }, ref ! _, () => ref ! PoisonPill)
    }
  }

  def apply[R](ids: Iterator[Id]): Flow[Response,RequestMessage,R with RemoteConnection] =
    macro RPCInterfaceMacros.remoteImpl[R]
}
