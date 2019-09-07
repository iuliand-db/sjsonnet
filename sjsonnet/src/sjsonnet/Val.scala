package sjsonnet

import sjsonnet.Expr.Member.Visibility
import sjsonnet.Expr.Params

import scala.annotation.tailrec
import scala.reflect.ClassTag

object Lazy{
  def apply(calc0: => Val) = new Lazy(calc0)
}
class Lazy(calc0: => Val){
  lazy val force = calc0
}
sealed trait Val{
  def prettyName: String
  def cast[T: ClassTag: PrettyNamed] =
    if (implicitly[ClassTag[T]].runtimeClass.isInstance(this)) this.asInstanceOf[T]
    else throw new DelegateError(
      "Expected " + implicitly[PrettyNamed[T]].s + ", found " + prettyName
    )
}
class PrettyNamed[T](val s: String)
object PrettyNamed{
  implicit def strName: PrettyNamed[Val.Str] = new PrettyNamed("string")
  implicit def numName: PrettyNamed[Val.Num] = new PrettyNamed("number")
  implicit def arrName: PrettyNamed[Val.Arr] = new PrettyNamed("array")
  implicit def objName: PrettyNamed[Val.Obj] = new PrettyNamed("object")
  implicit def funName: PrettyNamed[Val.Func] = new PrettyNamed("function")
}
object Val{
  def bool(b: Boolean) = if (b) True else False
  sealed trait Bool extends Val{
  }
  case object True extends Bool{
    def prettyName = "boolean"
  }
  case object False extends Bool{
    def prettyName = "boolean"
  }
  case object Null extends Val{
    def prettyName = "null"
  }
  case class Str(value: String) extends Val{
    def prettyName = "string"
  }
  case class Num(value: Double) extends Val{
    def prettyName = "number"
  }
  case class Arr(value: Seq[Lazy]) extends Val{
    def prettyName = "array"
  }
  object Obj{

    case class Member(add: Boolean,
                      visibility: Visibility,
                      invoke: (Obj, Option[Obj], FileScope) => Val,
                      cached: Boolean = true)

    def merge(lhs: Val.Obj, rhs: Val.Obj) = {
      def rec(current: Val.Obj): Val.Obj = {
        current.`super` match{
          case None => Val.Obj(current.value0, _ => (), Some(lhs))
          case Some(x) => Val.Obj(current.value0, _ => (), Some(rec(x)))
        }
      }
      rec(rhs)
    }
  }
  case class Obj(value0: Map[String, Obj.Member],
                 triggerAsserts: Val.Obj => Unit,
                 `super`: Option[Obj]) extends Val{
    def prettyName = "object"

    def getVisibleKeys() = {
      def rec(current: Val.Obj): Seq[(String, Visibility)] = {
        current.`super`.toSeq.flatMap(rec) ++
        current.value0.map{case (k, m) => (k, m.visibility)}.toSeq
      }

      val mapping = collection.mutable.LinkedHashMap.empty[String, Boolean]
      for ((k, sep) <- rec(this)){
        (mapping.get(k), sep) match{
          case (None, Visibility.Hidden) => mapping(k) = true
          case (None, _)    => mapping(k) = false

          case (Some(false), Visibility.Hidden) => mapping(k) = true
          case (Some(true), Visibility.Unhide) => mapping(k) = false
          case (Some(x), _) => mapping(k) = x
        }
      }
      mapping
    }
    val valueCache = collection.mutable.Map.empty[Any, Val]
    def value(k: String,
              offset: Int,
              self: Obj = this)
             (implicit fileScope: FileScope, evaluator: EvalScope): Val = {

      val cacheKey = if(self eq this) k else (k, self)

      val cacheLookuped =
        if (value0.get(k).exists(_.cached == false)) None
        else valueCache.get(cacheKey)

      cacheLookuped match{
        case Some(res) => res
        case None =>
          valueRaw(k, self, offset) match{
            case Some(x) =>
              valueCache(cacheKey) = x
              x
            case None =>
              Util.fail("Field does not exist: " + k, offset)
          }
      }
    }

    def mergeMember(l: Val,
                    r: Val,
                    offset: Int)
                   (implicit fileScope: FileScope, evaluator: EvalScope) = (l, r) match{
      case (Val.Str(l), Val.Str(r)) => Val.Str(l + r)
      case (Val.Num(l), Val.Num(r)) => Val.Num(l + r)
      case (Val.Arr(l), Val.Arr(r)) => Val.Arr(l ++ r)
      case (l: Val.Obj, r: Val.Obj) => Val.Obj.merge(l, r)
      case (Val.Str(l), r) =>
        try Val.Str(l + evaluator.materialize(r).transform(new Renderer()).toString)
        catch Util.tryCatch2(offset)
      case (l, Val.Str(r)) =>
        try Val.Str(evaluator.materialize(l).transform(new Renderer()).toString + r)
        catch Util.tryCatch2(offset)
    }

    @tailrec final def valueCached(k: String): Option[Boolean] = this.value0.get(k) match{
      case Some(m) => Some(m.cached)

      case None => this.`super` match{
        case None => None
        case Some(s) => s.valueCached(k)
      }
    }

    def valueRaw(k: String,
                 self: Obj,
                 offset: Int)
                (implicit fileScope: FileScope, evaluator: EvalScope): Option[Val] = this.value0.get(k) match{
      case Some(m) =>
        this.`super` match{
          case Some(s) if m.add =>
            Some(
              s.valueRaw(k, self, offset) match{
                case None => m.invoke(self, this.`super`, fileScope)
                case Some(x) =>
                  mergeMember(
                    x,
                    m.invoke(self, this.`super`, fileScope),
                    offset
                  )
              }
            )
          case _ =>
            Some(m.invoke(self, this.`super`, fileScope))
        }

      case None => this.`super` match{
        case None => None
        case Some(s) => s.valueRaw(k, self, offset)
      }
    }
  }

  case class Func(scopes: Option[(ValScope, FileScope)],
                  params: Params,
                  evalRhs: (ValScope, String, EvalScope, FileScope, Int) => Val,
                  evalDefault: (Expr, ValScope, EvalScope) => Val = null) extends Val{
    def prettyName = "function"
    def apply(args: Seq[(Option[String], Lazy)],
              thisFile: String,
              outerOffset: Int)
             (implicit fileScope: FileScope, evaluator: EvalScope) = {

      val argIndices = params.args.map{case (k, d, i) => (k, i)}.toMap
      lazy val defaultArgsBindings =
        params.args.collect{
          case (k, Some(default), index) =>
            (
              index,
              (self: Val.Obj, sup: Option[Val.Obj]) =>
                Lazy(evalDefault(default, newScope, evaluator))
            )
        }

      lazy val passedArgsBindings = try
        args.zipWithIndex.map{
          case ((Some(name), v), _) =>
            (
              argIndices.getOrElse(
                name,
                Util.fail(
                  s"Function has no parameter $name",
                  outerOffset
                )
              ),
              (self: Val.Obj, sup: Option[Val.Obj]) => v
            )
          case ((None, v), i) =>
            (
              params.args(i)._3,
              (self: Val.Obj, sup: Option[Val.Obj]) => v
            )
        }
      catch{
        case e: IndexOutOfBoundsException =>
          Util.fail(
            "Too many args, function has " + params.args.length + " parameter(s)",
            outerOffset
          )
      }
      lazy val seen = collection.mutable.Set.empty[Int]
      for((k, v) <- passedArgsBindings){
        if (seen(k)) Util.fail("Parameter passed more than once: " + k, outerOffset)
        else seen.add(k)
      }

      lazy val missing = params.args.collect{case (_, None, i) => i}.toSet -- seen
      if (missing.nonEmpty){
        val plural = if (missing.size > 1) "s" else ""
        val names = missing.map(fileScope.indexNames).mkString(", ")
        Util.fail(
          s"Function parameter$plural $names not bound in call" ,
          outerOffset
        )
      }

      lazy val unexpectedParams = seen -- params.args.collect{case (_, _, i) => i}.toSet
      if (unexpectedParams.nonEmpty) {
        val plural = if (unexpectedParams.size > 1) "s" else ""
        val names = unexpectedParams.map(fileScope.indexNames).mkString(", ")
        Util.fail(
          s"Function has no parameter$plural $names",
          outerOffset
        )
      }

      lazy val newScope: ValScope = scopes match{
        case None =>
          ValScope.empty(args.length + 1) ++ defaultArgsBindings ++ passedArgsBindings
        case Some((s, fs)) =>
          s ++ defaultArgsBindings ++ passedArgsBindings
      }
      evalRhs(newScope, thisFile, evaluator, fileScope, outerOffset)
    }
  }
}

abstract class EvalScope(val extVars: Map[String, ujson.Value], val wd: Path){
  def visitExpr(expr: Expr)
               (implicit scope: ValScope, fileScope: FileScope): Val

  def materialize(v: Val): ujson.Value
}
class FileScope(val currentFile: Path,
                val currentRoot: Path,
                val nameIndices: Map[String, Int]){
  val indexNames = nameIndices.map(_.swap)
}
object ValScope{
  def empty(size: Int) = {
    new ValScope(None, None, None, new Array(size), None)
  }
}

case class ValScope(dollar0: Option[Val.Obj],
                    self0: Option[Val.Obj],
                    super0: Option[Val.Obj],
                    bindings0: Array[Lazy],
                    delegate: Option[ValScope]) {
  def dollar = dollar0.get
  def self = self0.get
  val bindingCache = collection.mutable.Map.empty[Int, Option[Lazy]]
  def bindings(k: Int): Option[Lazy] = bindingCache.getOrElseUpdate(
    k,
    bindings0.lift(k).filter(_ != null).orElse(delegate.flatMap(_.bindings(k)))
  )

  def ++(traversableOnce: TraversableOnce[(Int, (Val.Obj, Option[Val.Obj]) => Lazy)]) = {
    val newBindings = java.util.Arrays.copyOf(bindings0, bindings0.length)
    for((i, v) <- traversableOnce) newBindings(i) = v.apply(self0.getOrElse(null), super0)
    new ValScope(
      dollar0,
      self0,
      super0,
      newBindings,
      Some(this),
    )
  }
}

object Util {
  def tryCatch[T](offset: Int)
                 (implicit fileScope: FileScope, evaluator: EvalScope): PartialFunction[Throwable, Nothing] = {
    case e: Error => throw e
    case e: DelegateError =>
      throw new Error(e.msg, Nil, None)
        .addFrame(fileScope.currentFile, evaluator.wd, offset)
    case e: Throwable =>
      throw new Error("Internal Error", Nil, Some(e))
        .addFrame(fileScope.currentFile, evaluator.wd, offset)
  }
  def tryCatch2[T](offset: Int)
                  (implicit fileScope: FileScope, evaluator: EvalScope): PartialFunction[Throwable, Nothing] = {
    case e: Error => throw e.addFrame(fileScope.currentFile, evaluator.wd, offset)
    case e: DelegateError =>
      throw new Error(e.msg, Nil, None)
        .addFrame(fileScope.currentFile, evaluator.wd, offset)
    case e: Throwable =>
      throw new Error("Internal Error", Nil, Some(e))
        .addFrame(fileScope.currentFile, evaluator.wd, offset)
  }
  def fail(msg: String, offset: Int)
          (implicit fileScope: FileScope, evaluator: EvalScope) = {
    throw Error(msg, Nil, None).addFrame(fileScope.currentFile, evaluator.wd, offset)
  }
}