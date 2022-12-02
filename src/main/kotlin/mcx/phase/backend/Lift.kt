package mcx.phase.backend

import mcx.ast.Annotation
import mcx.phase.Context
import mcx.phase.UnexpectedHole
import mcx.phase.backend.Lift.Env.Companion.emptyEnv
import mcx.phase.prettyType
import mcx.ast.Core as C
import mcx.ast.Lifted as L

class Lift private constructor(
  private val context: Context,
  private val definition: C.Definition,
) {
  private val liftedDefinitions: MutableList<L.Definition> = mutableListOf()
  private var freshFunctionId: Int = 0

  private fun lift(): List<L.Definition> {
    val annotations = definition.annotations.mapNotNull { liftAnnotation(it) }
    when (definition) {
      is C.Definition.Resource -> {
        val body = emptyEnv().liftTerm(definition.body)
        liftedDefinitions += L.Definition.Resource(annotations, definition.registry, definition.name, body)
      }
      is C.Definition.Function -> {
        val env = emptyEnv()
        if (L.Annotation.BUILTIN in annotations) {
          val param = liftType(definition.binder.type)
          val result = liftType(definition.result)
          liftedDefinitions += L.Definition.Builtin(annotations, definition.name, param, result)
        } else {
          val binder = env.liftPattern(definition.binder)
          val body = env.liftTerm(definition.body)
          liftedDefinitions += L.Definition.Function(annotations, definition.name, binder, body, null)
        }
      }
      is C.Definition.Type     -> Unit
    }
    return liftedDefinitions
  }

  private fun liftAnnotation(
    annotation: Annotation,
  ): L.Annotation? {
    return when (annotation) {
      Annotation.EXPORT  -> null
      Annotation.TICK    -> L.Annotation.TICK
      Annotation.LOAD    -> L.Annotation.LOAD
      Annotation.NO_DROP -> L.Annotation.NO_DROP
      Annotation.INLINE  -> error("unexpected: $annotation")
      Annotation.STATIC  -> error("unexpected: $annotation")
      Annotation.BUILTIN -> L.Annotation.BUILTIN
      Annotation.HOLE    -> throw UnexpectedHole
    }
  }

  private fun liftType(
    type: C.Type,
  ): L.Type {
    return when (type) {
      is C.Type.Bool      -> L.Type.Bool(type.value)
      is C.Type.Byte      -> L.Type.Byte(type.value)
      is C.Type.Short     -> L.Type.Short(type.value)
      is C.Type.Int       -> L.Type.Int(type.value)
      is C.Type.Long      -> L.Type.Long(type.value)
      is C.Type.Float     -> L.Type.Float(type.value)
      is C.Type.Double    -> L.Type.Double(type.value)
      is C.Type.String    -> L.Type.String(type.value)
      is C.Type.ByteArray -> L.Type.ByteArray
      is C.Type.IntArray  -> L.Type.IntArray
      is C.Type.LongArray -> L.Type.LongArray
      is C.Type.List      -> L.Type.List(liftType(type.element))
      is C.Type.Compound  -> L.Type.Compound(type.elements.mapValues { liftType(it.value) })
      is C.Type.Ref       -> L.Type.Ref(liftType(type.element))
      is C.Type.Tuple     -> L.Type.Tuple(type.elements.map { liftType(it) })
      is C.Type.Fun       -> L.Type.Fun(liftType(type.param), liftType(type.result))
      is C.Type.Union     -> L.Type.Union(type.elements.map { liftType(it) })
      is C.Type.Code      -> error("unexpected: ${prettyType(type)}")
      is C.Type.Var       -> error("unexpected: ${prettyType(type)}")
      is C.Type.Run       -> error("unexpected: ${prettyType(type)}")
      is C.Type.Meta      -> error("unexpected: ${prettyType(type)}")
      is C.Type.Hole      -> throw UnexpectedHole
    }
  }

  private fun Env.liftTerm(
    term: C.Term,
  ): L.Term {
    val type = liftType(term.type)
    return when (term) {
      is C.Term.BoolOf      -> L.Term.BoolOf(term.value, type)
      is C.Term.ByteOf      -> L.Term.ByteOf(term.value, type)
      is C.Term.ShortOf     -> L.Term.ShortOf(term.value, type)
      is C.Term.IntOf       -> L.Term.IntOf(term.value, type)
      is C.Term.LongOf      -> L.Term.LongOf(term.value, type)
      is C.Term.FloatOf     -> L.Term.FloatOf(term.value, type)
      is C.Term.DoubleOf    -> L.Term.DoubleOf(term.value, type)
      is C.Term.StringOf    -> L.Term.StringOf(term.value, type)
      is C.Term.ByteArrayOf -> L.Term.ByteArrayOf(term.elements.map { liftTerm(it) }, type)
      is C.Term.IntArrayOf  -> L.Term.IntArrayOf(term.elements.map { liftTerm(it) }, type)
      is C.Term.LongArrayOf -> L.Term.LongArrayOf(term.elements.map { liftTerm(it) }, type)
      is C.Term.ListOf      -> L.Term.ListOf(term.elements.map { liftTerm(it) }, type)
      is C.Term.CompoundOf  -> L.Term.CompoundOf(term.elements.mapValues { liftTerm(it.value) }, type)
      is C.Term.RefOf       -> L.Term.RefOf(liftTerm(term.element), type)
      is C.Term.TupleOf     -> L.Term.TupleOf(term.elements.map { liftTerm(it) }, type)
      is C.Term.FunOf       ->
        // remap levels
        with(emptyEnv()) {
          val binder = liftPattern(term.binder)
          val binderSize = types.size
          val freeVars = freeVars(term)
          freeVars.forEach { (name, type) -> bind(name, type.second) }
          val capture = L.Pattern.CompoundOf(
            freeVars.entries.mapIndexed { index, (name, type) -> name to L.Pattern.Var(binderSize + index, emptyList(), type.second) },
            emptyList(),
            L.Type.Compound(freeVars.mapValues { it.value.second }),
          )
          val body = liftTerm(term.body)
          val tag = context.liftedFunctions.size
          val bodyFunction = L.Definition
            .Function(
              emptyList(),
              definition.name.module / "${definition.name.name}:${freshFunctionId++}",
              L.Pattern.TupleOf(listOf(binder, capture), emptyList(), L.Type.Tuple(listOf(binder.type, capture.type))),
              body,
              tag,
            )
            .also { liftedDefinitions += it }
          context.liftFunction(bodyFunction)
          L.Term.FunOf(tag, freeVars.entries.map { (name, type) -> Triple(name, type.first, type.second) }, type)
        }
      is C.Term.Apply       -> {
        val operator = liftTerm(term.operator)
        val arg = liftTerm(term.arg)
        L.Term.Run(Context.DISPATCH, L.Term.TupleOf(listOf(arg, operator), L.Type.Tuple(listOf(arg.type, operator.type))), type)
      }
      is C.Term.If          -> {
        val condition = liftTerm(term.condition)
        val thenFunction = createFreshFunction(liftTerm(term.thenClause), 1)
        val elseFunction = createFreshFunction(liftTerm(term.elseClause), null)
        L.Term.If(condition, thenFunction.name, elseFunction.name, type)
      }
      is C.Term.Let         -> {
        val init = liftTerm(term.init)
        val (binder, body) = restoring {
          val binder = liftPattern(term.binder)
          val body = liftTerm(term.body)
          binder to body
        }
        L.Term.Let(binder, init, body, type)
      }
      is C.Term.Var         -> L.Term.Var(this[term.name], type)
      is C.Term.Run         -> L.Term.Run(term.name, liftTerm(term.arg), type)
      is C.Term.Is          ->
        restoring {
          L.Term.Is(liftTerm(term.scrutinee), liftPattern(term.scrutineer), type)
        }
      is C.Term.Command     -> L.Term.Command(term.value, type)
      is C.Term.CodeOf      -> error("unexpected: code_of")
      is C.Term.Splice      -> error("unexpected: splice")
      is C.Term.Hole        -> throw UnexpectedHole
    }
  }

  private fun Env.liftPattern(
    pattern: C.Pattern,
  ): L.Pattern {
    val annotations = pattern.annotations.mapNotNull { liftAnnotation(it) }
    val type = liftType(pattern.type)
    return when (pattern) {
      is C.Pattern.IntOf      -> L.Pattern.IntOf(pattern.value, annotations, type)
      is C.Pattern.IntRangeOf -> L.Pattern.IntRangeOf(pattern.min, pattern.max, annotations, type)
      is C.Pattern.CompoundOf -> L.Pattern.CompoundOf(pattern.elements.map { (name, element) -> name to liftPattern(element) }, annotations, type)
      is C.Pattern.TupleOf    -> L.Pattern.TupleOf(pattern.elements.map { liftPattern(it) }, annotations, type)
      is C.Pattern.Var        -> {
        bind(pattern.name, type)
        L.Pattern.Var(types.lastIndex, annotations, type)
      }
      is C.Pattern.Drop       -> L.Pattern.Drop(annotations, type)
      is C.Pattern.Hole       -> throw UnexpectedHole
    }
  }

  private fun freeVars(
    term: C.Term,
  ): LinkedHashMap<String, Pair<Int, L.Type>> {
    return when (term) {
      is C.Term.BoolOf      -> linkedMapOf()
      is C.Term.ByteOf      -> linkedMapOf()
      is C.Term.ShortOf     -> linkedMapOf()
      is C.Term.IntOf       -> linkedMapOf()
      is C.Term.LongOf      -> linkedMapOf()
      is C.Term.FloatOf     -> linkedMapOf()
      is C.Term.DoubleOf    -> linkedMapOf()
      is C.Term.StringOf    -> linkedMapOf()
      is C.Term.ByteArrayOf -> term.elements.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.IntArrayOf  -> term.elements.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.LongArrayOf -> term.elements.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.ListOf      -> term.elements.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.CompoundOf  -> term.elements.values.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.RefOf       -> freeVars(term.element)
      is C.Term.TupleOf     -> term.elements.fold(linkedMapOf()) { acc, element -> acc.also { it += freeVars(element) } }
      is C.Term.FunOf       -> freeVars(term.body).also { it -= boundVars(term.binder) }
      is C.Term.Apply       -> freeVars(term.operator).also { it += freeVars(term.arg) }
      is C.Term.If          -> freeVars(term.condition).also { it += freeVars(term.thenClause); it += freeVars(term.elseClause) }
      is C.Term.Let         -> freeVars(term.init).also { it += freeVars(term.body); it -= boundVars(term.binder) }
      is C.Term.Var         -> linkedMapOf(term.name to (term.level to liftType(term.type)))
      is C.Term.Run         -> freeVars(term.arg)
      is C.Term.Is          -> freeVars(term.scrutinee)
      is C.Term.Command     -> linkedMapOf()
      is C.Term.CodeOf      -> error("unexpected: code_of")
      is C.Term.Splice      -> error("unexpected: splice")
      is C.Term.Hole        -> throw UnexpectedHole
    }
  }

  private fun boundVars(
    pattern: C.Pattern,
  ): Set<String> {
    return when (pattern) {
      is C.Pattern.IntOf      -> emptySet()
      is C.Pattern.IntRangeOf -> emptySet()
      is C.Pattern.CompoundOf -> pattern.elements.values.flatMapTo(hashSetOf()) { boundVars(it) }
      is C.Pattern.TupleOf    -> pattern.elements.flatMapTo(hashSetOf()) { boundVars(it) }
      is C.Pattern.Var        -> setOf(pattern.name)
      is C.Pattern.Drop       -> emptySet()
      is C.Pattern.Hole       -> throw UnexpectedHole
    }
  }

  private fun Env.createFreshFunction(
    body: L.Term,
    restore: Int?,
  ): L.Definition.Function {
    val type = L.Type.Tuple(types.map { (_, type) -> type })
    val params = types.mapIndexed { level, (_, type) ->
      L.Pattern.Var(level, emptyList(), type)
    }
    return L.Definition
      .Function(
        emptyList(),
        definition.name.module / "${definition.name.name}:${freshFunctionId++}",
        L.Pattern.TupleOf(params, listOf(L.Annotation.NO_DROP), type),
        body,
        restore,
      )
      .also { liftedDefinitions += it }
  }

  private class Env private constructor() {
    private var savedSize: Int = 0
    private val _types: MutableList<Pair<String, L.Type>> = mutableListOf()
    val types: List<Pair<String, L.Type>> get() = _types

    operator fun get(
      name: String,
    ): Int =
      _types.indexOfLast { it.first == name }

    fun bind(
      name: String,
      type: L.Type,
    ) {
      _types += name to type
    }

    inline fun <R> restoring(
      action: () -> R,
    ): R {
      savedSize = _types.size
      val result = action()
      repeat(_types.size - savedSize) {
        _types.removeLast()
      }
      return result
    }

    companion object {
      fun emptyEnv(): Env =
        Env()
    }
  }

  companion object {
    operator fun invoke(
      context: Context,
      definition: C.Definition,
    ): List<L.Definition> =
      Lift(context, definition).lift()
  }
}
