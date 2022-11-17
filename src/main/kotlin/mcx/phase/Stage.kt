package mcx.phase

import mcx.ast.Location
import mcx.ast.Value
import mcx.phase.Stage.Env.Companion.emptyEnv
import kotlin.math.max
import mcx.ast.Core as C

class Stage private constructor(
  private val dependencies: Map<Location, C.Resource>,
) {
  private fun stageResource(
    resource: C.Resource,
  ): C.Resource? {
    return when (resource) {
      is C.Resource.JsonResource -> resource
      is C.Resource.Function     ->
        if (max(getMinStage(resource.param), getMinStage(resource.result)) == 0) {
          C.Resource
            .Function(resource.annotations, resource.name, resource.binder, resource.param, resource.result)
            .also {
              it.body = stageTerm(resource.body)
            }
        } else {
          null
        }
      is C.Resource.Hole         -> unexpectedHole()
    }
  }

  private fun getMinStage(
    type: C.Type,
  ): Int {
    return when (type) {
      is C.Type.End       -> 0
      is C.Type.Bool      -> 0
      is C.Type.Byte      -> 0
      is C.Type.Short     -> 0
      is C.Type.Int       -> 0
      is C.Type.Long      -> 0
      is C.Type.Float     -> 0
      is C.Type.Double    -> 0
      is C.Type.String    -> 0
      is C.Type.ByteArray -> 0
      is C.Type.IntArray  -> 0
      is C.Type.LongArray -> 0
      is C.Type.List      -> getMinStage(type.element)
      is C.Type.Compound  -> type.elements.values.maxOfOrNull { getMinStage(it) } ?: 0
      is C.Type.Ref       -> getMinStage(type.element)
      is C.Type.Tuple     -> type.elements.maxOfOrNull { getMinStage(it) } ?: 0
      is C.Type.Code      -> getMinStage(type.element) + 1
      is C.Type.Hole      -> unexpectedHole()
    }
  }

  private fun stageTerm(
    term: C.Term,
  ): C.Term {
    return when (term) {
      is C.Term.BoolOf      -> term
      is C.Term.ByteOf      -> term
      is C.Term.ShortOf     -> term
      is C.Term.IntOf       -> term
      is C.Term.LongOf      -> term
      is C.Term.FloatOf     -> term
      is C.Term.DoubleOf    -> term
      is C.Term.StringOf    -> term
      is C.Term.ByteArrayOf -> C.Term.ByteArrayOf(term.elements.map { stageTerm(it) }, term.type)
      is C.Term.IntArrayOf  -> C.Term.IntArrayOf(term.elements.map { stageTerm(it) }, term.type)
      is C.Term.LongArrayOf -> C.Term.LongArrayOf(term.elements.map { stageTerm(it) }, term.type)
      is C.Term.ListOf     -> C.Term.ListOf(term.elements.map { stageTerm(it) }, term.type)
      is C.Term.CompoundOf -> C.Term.CompoundOf(term.elements.mapValues { stageTerm(it.value) }, term.type)
      is C.Term.RefOf      -> C.Term.RefOf(stageTerm(term.element), term.type)
      is C.Term.TupleOf    -> C.Term.TupleOf(term.elements.map { stageTerm(it) }, term.type)
      is C.Term.If         -> C.Term.If(stageTerm(term.condition), stageTerm(term.thenClause), stageTerm(term.elseClause), term.type)
      is C.Term.Let        -> C.Term.Let(term.binder, stageTerm(term.init), stageTerm(term.body), term.type)
      is C.Term.Var        -> term
      is C.Term.Run        -> C.Term.Run(term.name, stageTerm(term.arg), term.type)
      is C.Term.Is         -> C.Term.Is(stageTerm(term.scrutinee), term.scrutineer, term.type)
      is C.Term.Command    -> term
      is C.Term.CodeOf     -> term
      is C.Term.Splice     -> quoteValue(emptyEnv().evalTerm(term), term.type)
      is C.Term.Hole       -> unexpectedHole()
    }
  }

  // TODO: laziness
  private fun Env.evalTerm(
    term: C.Term,
  ): Value {
    return when (term) {
      is C.Term.BoolOf      -> Value.BoolOf(term.value)
      is C.Term.ByteOf      -> Value.ByteOf(term.value)
      is C.Term.ShortOf     -> Value.ShortOf(term.value)
      is C.Term.IntOf       -> Value.IntOf(term.value)
      is C.Term.LongOf      -> Value.LongOf(term.value)
      is C.Term.FloatOf     -> Value.FloatOf(term.value)
      is C.Term.DoubleOf    -> Value.DoubleOf(term.value)
      is C.Term.StringOf    -> Value.StringOf(term.value)
      is C.Term.ByteArrayOf -> Value.ByteArrayOf(term.elements.map { lazy { evalTerm(it) } })
      is C.Term.IntArrayOf  -> Value.IntArrayOf(term.elements.map { lazy { evalTerm(it) } })
      is C.Term.LongArrayOf -> Value.LongArrayOf(term.elements.map { lazy { evalTerm(it) } })
      is C.Term.ListOf      -> Value.ListOf(term.elements.map { lazy { evalTerm(it) } })
      is C.Term.CompoundOf  -> Value.CompoundOf(term.elements.mapValues { lazy { evalTerm(it.value) } })
      is C.Term.RefOf       -> Value.RefOf(lazy { evalTerm(term.element) })
      is C.Term.TupleOf     -> Value.TupleOf(term.elements.map { lazy { evalTerm(it) } })
      is C.Term.If          ->
        when (val condition = evalTerm(term.condition)) {
          is Value.BoolOf -> if (condition.value) evalTerm(term.thenClause) else evalTerm(term.elseClause)
          else            -> Value.If(condition, lazy { evalTerm(term.thenClause) }, lazy { evalTerm(term.elseClause) })
        }
      is C.Term.Let         ->
        restoring {
          bindValue(evalTerm(term.init), term.binder)
          evalTerm(term.body)
        }
      is C.Term.Var         -> this[term.level].value
      is C.Term.Run         ->
        restoring {
          val resource = dependencies[term.name] as C.Resource.Function
          if (C.Annotation.Builtin in resource.annotations) {
            val builtin = requireNotNull(BUILTINS[resource.name]) { "builtin not found: '${resource.name}'" }
            builtin.eval(evalTerm(term.arg))
          } else {
            bind(lazy { evalTerm(term.arg) })
            evalTerm(resource.body)
          }
        }
      is C.Term.Is          -> {
        val scrutinee = evalTerm(term.scrutinee)
        when (val matched = matchValue(scrutinee, term.scrutineer)) {
          null -> Value.Is(scrutinee, term.scrutineer, term.scrutinee.type)
          else -> Value.BoolOf(matched)
        }
      }
      is C.Term.Command     -> error("unexpected: command") // TODO
      is C.Term.CodeOf      -> Value.CodeOf(lazy { evalTerm(term.element) })
      is C.Term.Splice      ->
        when (val element = evalTerm(term.element)) {
          is Value.CodeOf -> element.element.value
          else            -> Value.Splice(element, term.element.type)
        }
      is C.Term.Hole        -> unexpectedHole()
    }
  }

  private fun Env.bindValue(
    value: Value,
    binder: C.Pattern,
  ) {
    return when {
      value is Value.TupleOf &&
      binder is C.Pattern.TupleOf -> (value.elements zip binder.elements).forEach { (value, binder) -> bindValue(value.value, binder) }

      binder is C.Pattern.Var     -> bind(lazyOf(value))

      else                        -> Unit
    }
  }

  private fun matchValue(
    value: Value,
    pattern: C.Pattern,
  ): Boolean? {
    return when {
      value is Value.IntOf &&
      pattern is C.Pattern.IntOf      -> value.value == pattern.value

      value is Value.IntOf &&
      pattern is C.Pattern.IntRangeOf -> value.value in pattern.min..pattern.max

      value is Value.TupleOf &&
      pattern is C.Pattern.TupleOf    -> (value.elements zip pattern.elements).all { (value, pattern) -> matchValue(value.value, pattern) == true }

      pattern is C.Pattern.Var        -> true

      pattern is C.Pattern.Drop       -> true

      pattern is C.Pattern.Hole       -> unexpectedHole()

      else                            -> null
    }
  }

  private fun quoteValue(
    value: Value,
    type: C.Type,
  ): C.Term {
    return when (value) {
      is Value.BoolOf      -> C.Term.BoolOf(value.value, C.Type.Bool)
      is Value.ByteOf      -> C.Term.ByteOf(value.value, C.Type.Byte)
      is Value.ShortOf     -> C.Term.ShortOf(value.value, C.Type.Short)
      is Value.IntOf       -> C.Term.IntOf(value.value, C.Type.Int)
      is Value.LongOf      -> C.Term.LongOf(value.value, C.Type.Long)
      is Value.FloatOf     -> C.Term.FloatOf(value.value, C.Type.Float)
      is Value.DoubleOf    -> C.Term.DoubleOf(value.value, C.Type.Double)
      is Value.StringOf    -> C.Term.StringOf(value.value, C.Type.String)
      is Value.ByteArrayOf -> C.Term.ByteArrayOf(value.elements.map { quoteValue(it.value, C.Type.Byte) }, C.Type.ByteArray)
      is Value.IntArrayOf  -> C.Term.IntArrayOf(value.elements.map { quoteValue(it.value, C.Type.Int) }, C.Type.IntArray)
      is Value.LongArrayOf -> C.Term.LongArrayOf(value.elements.map { quoteValue(it.value, C.Type.Long) }, C.Type.LongArray)
      is Value.ListOf      -> {
        type as C.Type.List
        C.Term.ListOf(value.elements.map { quoteValue(it.value, type.element) }, type)
      }
      is Value.CompoundOf  -> {
        type as C.Type.Compound
        C.Term.CompoundOf(value.elements.mapValues { (key, element) -> quoteValue(element.value, type.elements[key]!!) }, type)
      }
      is Value.RefOf       -> {
        type as C.Type.Ref
        C.Term.RefOf(quoteValue(value.element.value, type.element), type)
      }
      is Value.TupleOf     -> {
        type as C.Type.Tuple
        C.Term.TupleOf(value.elements.mapIndexed { index, element -> quoteValue(element.value, type.elements[index]) }, type)
      }
      is Value.If          -> C.Term.If(quoteValue(value.condition, C.Type.Bool), quoteValue(value.thenClause.value, type), quoteValue(value.elseClause.value, type), type)
      is Value.Var         -> C.Term.Var(value.name, value.level, type)
      is Value.Is          -> C.Term.Is(quoteValue(value.scrutinee, value.scrutineeType), value.scrutineer, C.Type.Bool)
      is Value.CodeOf      -> {
        type as C.Type.Code
        C.Term.CodeOf(quoteValue(value.element.value, type.element), type)
      }
      is Value.Splice      -> C.Term.Splice(quoteValue(value.element, value.elementType), type)
    }
  }

  private fun unexpectedHole(): Nothing =
    error("unexpected: hole")

  private class Env private constructor() {
    private val values: MutableList<Lazy<Value>> = mutableListOf()
    private var savedSize: Int = 0

    operator fun get(
      level: Int,
    ): Lazy<Value> =
      values[level]

    fun bind(
      value: Lazy<Value>,
    ) {
      values += value
    }

    inline fun <R> restoring(
      action: () -> R,
    ): R {
      savedSize = values.size
      val result = action()
      repeat(values.size - savedSize) {
        values.removeLast()
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
      config: Config,
      dependencies: Map<Location, C.Resource>,
      resource: C.Resource,
    ): C.Resource? =
      Stage(dependencies).stageResource(resource)
  }
}
