package mcx.phase.backend

import mcx.ast.DefinitionLocation
import mcx.ast.ModuleLocation
import mcx.ast.Packed
import mcx.ast.Packed.Command
import mcx.ast.Packed.Command.*
import mcx.ast.Packed.Command.Execute.ConditionalScore.Comparator.*
import mcx.ast.Packed.Command.Execute.Mode.*
import mcx.ast.Packed.Command.Execute.StoreStorage.Type
import mcx.ast.Packed.DataAccessor
import mcx.ast.Packed.DataManipulator
import mcx.ast.Packed.Operation.*
import mcx.ast.Packed.SourceProvider
import mcx.data.Nbt
import mcx.data.ResourceLocation
import mcx.phase.*
import mcx.phase.Context.Companion.DISPATCH
import mcx.util.nbtPath
import mcx.ast.Lifted as L
import mcx.ast.Packed as P

class Pack private constructor(
  private val context: Context,
) {
  private val commands: MutableList<Command> = mutableListOf()
  private val entries: Map<P.Stack, MutableList<Int?>> =
    P.Stack
      .values()
      .associateWith { mutableListOf() }

  private fun packDefinition(
    definition: L.Definition,
  ): P.Definition {
    val path = packDefinitionLocation(definition.name)
    return when (definition) {
      is L.Definition.Function -> {
        !{ Raw("# function ${definition.name}") }

        val binderTypes = eraseType(definition.binder.type)
        binderTypes.forEach { push(it, null) }
        packPattern(definition.binder)

        packTerm(definition.body)

        if (L.Modifier.NO_DROP !in definition.modifiers) {
          val resultTypes = eraseType(definition.body.type)
          dropPattern(definition.binder, resultTypes)
        }

        if (definition.restore != null) {
          +SetScore(REG_0, REG, definition.restore)
        }

        P.Definition.Function(path, commands)
      }
      is L.Definition.Test     -> {
        !{ Raw("# test ${definition.name}") }

        packTerm(definition.body)
        +Raw("# todo")

        P.Definition.Function(path, commands)
      }
    }
  }

  private fun packTerm(
    term: L.Term,
  ) {
    when (term) {
      is L.Term.BoolOf      -> push(P.Stack.BYTE, SourceProvider.Value(Nbt.Byte(if (term.value) 1 else 0)))
      is L.Term.ByteOf      -> push(P.Stack.BYTE, SourceProvider.Value(Nbt.Byte(term.value)))
      is L.Term.ShortOf     -> push(P.Stack.SHORT, SourceProvider.Value(Nbt.Short(term.value)))
      is L.Term.IntOf       -> push(P.Stack.INT, SourceProvider.Value(Nbt.Int(term.value)))
      is L.Term.LongOf      -> push(P.Stack.LONG, SourceProvider.Value(Nbt.Long(term.value)))
      is L.Term.FloatOf     -> push(P.Stack.FLOAT, SourceProvider.Value(Nbt.Float(term.value)))
      is L.Term.DoubleOf    -> push(P.Stack.DOUBLE, SourceProvider.Value(Nbt.Double(term.value)))
      is L.Term.StringOf    -> push(P.Stack.STRING, SourceProvider.Value(Nbt.String(term.value)))
      is L.Term.ByteArrayOf -> {
        val elements = term.elements.map { (it as? L.Term.ByteOf)?.value ?: 0 }
        push(P.Stack.BYTE_ARRAY, SourceProvider.Value(Nbt.ByteArray(elements)))
        term.elements.forEachIndexed { index, element ->
          if (element !is L.Term.ByteOf) {
            packTerm(element)
            +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.BYTE_ARRAY.id)(-1)(index) }), DataManipulator.Set(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.BYTE.id)(-1) }))))
            drop(P.Stack.BYTE)
          }
        }
      }
      is L.Term.IntArrayOf  -> {
        val elements = term.elements.map { (it as? L.Term.IntOf)?.value ?: 0 }
        push(P.Stack.INT_ARRAY, SourceProvider.Value(Nbt.IntArray(elements)))
        term.elements.forEachIndexed { index, element ->
          if (element !is L.Term.IntOf) {
            packTerm(element)
            +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.INT_ARRAY.id)(-1)(index) }), DataManipulator.Set(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.INT.id)(-1) }))))
            drop(P.Stack.INT)
          }
        }
      }
      is L.Term.LongArrayOf -> {
        val elements = term.elements.map { (it as? L.Term.LongOf)?.value ?: 0 }
        push(P.Stack.LONG_ARRAY, SourceProvider.Value(Nbt.LongArray(elements)))
        term.elements.forEachIndexed { index, element ->
          if (element !is L.Term.LongOf) {
            packTerm(element)
            +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.LONG_ARRAY.id)(-1)(index) }), DataManipulator.Set(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.LONG.id)(-1) }))))
            drop(P.Stack.LONG)
          }
        }
      }
      is L.Term.ListOf      -> {
        push(P.Stack.LIST, SourceProvider.Value(Nbt.List.End))
        if (term.elements.isNotEmpty()) {
          val elementType = eraseType(term.elements.first().type).first()
          term.elements.forEach { element ->
            packTerm(element)
            val index = if (elementType == P.Stack.LIST) -2 else -1
            +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.LIST.id)(index) }), DataManipulator.Append(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(elementType.id)(-1) }))))
            drop(elementType)
          }
        }
      }
      is L.Term.CompoundOf  -> {
        push(P.Stack.COMPOUND, SourceProvider.Value(Nbt.Compound(emptyMap())))
        term.elements.forEach { (key, element) ->
          packTerm(element)
          val valueType = eraseType(element.type).first()
          val index = if (valueType == P.Stack.COMPOUND) -2 else -1
          +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.COMPOUND.id)(index)(key) }), DataManipulator.Set(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(valueType.id)(-1) }))))
          drop(valueType)
        }
      }
      is L.Term.RefOf       -> {
        packTerm(term.element)
        !{ Raw("# todo: $term") }
        push(P.Stack.INT, SourceProvider.Value(Nbt.Int(0)))
      }
      is L.Term.TupleOf     -> {
        term.elements.forEach { element ->
          packTerm(element)
        }
      }
      is L.Term.FunOf       -> {
        push(P.Stack.COMPOUND, SourceProvider.Value(Nbt.Compound(mapOf("_" to Nbt.Int(term.tag)))))
        term.vars.forEach { (name, level, type) ->
          val stack = eraseType(type).first()
          val index = this[level, stack]
          +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.COMPOUND.id)(-1)(name) }), DataManipulator.Set(SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(stack.id)(index) }))))
        }
      }
      is L.Term.If          -> {
        packTerm(term.condition)
        +Execute.StoreScore(RESULT, REG_0, REG, Execute.Run(GetData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.BYTE.id)(-1) }))))
        drop(P.Stack.BYTE)
        +Execute.ConditionalScoreMatches(
          true, REG_0, REG, 1..Int.MAX_VALUE,
          Execute.Run(
            RunFunction(packDefinitionLocation(term.thenName))
          )
        )
        +Execute.ConditionalScoreMatches(
          true, REG_0, REG, Int.MIN_VALUE..0,
          Execute.Run(
            RunFunction(packDefinitionLocation(term.elseName))
          )
        )
        eraseType(term.type).forEach { push(it, null) }
      }
      is L.Term.Let         -> {
        packTerm(term.init)
        packPattern(term.binder)
        packTerm(term.body)

        val bodyTypes = eraseType(term.body.type)
        dropPattern(term.binder, bodyTypes)
      }
      is L.Term.Var         -> {
        val type = eraseType(term.type).first()
        val index = this[term.level, type]
        push(type, SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(type.id)(index) })))
      }
      is L.Term.Run         -> {
        packTerm(term.arg)

        +RunFunction(packDefinitionLocation(term.name))
        eraseType(term.arg.type).forEach {
          drop(it, relevant = false)
        }
        eraseType(term.type).forEach { push(it, null) }
      }
      is L.Term.Is          -> {
        packTerm(term.scrutinee)
        matchPattern(term.scrutineer)
      }
      is L.Term.Command     -> {
        !{ Raw("# command") }
        +Raw(term.value)
        eraseType(term.type).forEach { push(it, null) }
      }
    }
  }

  private fun packPattern(
    pattern: L.Pattern,
  ) {
    when (pattern) {
      is L.Pattern.IntOf      -> Unit
      is L.Pattern.IntRangeOf -> Unit
      is L.Pattern.ListOf     -> {
        TODO()
      }
      is L.Pattern.CompoundOf -> {
        pattern.elements.forEach { (name, element) ->
          push(eraseType(element.type).first(), SourceProvider.From(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.COMPOUND.id)(-1)(name) }))) // TODO: avoid immediate push
          packPattern(element)
        }
      }
      is L.Pattern.TupleOf    ->
        pattern.elements
          .asReversed()
          .forEach { packPattern(it) }
      is L.Pattern.Var        -> bind(pattern.level, eraseType(pattern.type).first())
      is L.Pattern.Drop       -> Unit
    }
  }

  private fun dropPattern(
    pattern: L.Pattern,
    keeps: List<P.Stack>,
  ) {
    when (pattern) {
      is L.Pattern.IntOf      -> drop(P.Stack.INT, keeps)
      is L.Pattern.IntRangeOf -> drop(P.Stack.INT, keeps)
      is L.Pattern.ListOf     -> {
        TODO()
      }
      is L.Pattern.CompoundOf -> {
        pattern.elements
          .asReversed()
          .forEach { (_, element) -> dropPattern(element, keeps) }
        drop(P.Stack.COMPOUND, keeps)
      }
      is L.Pattern.TupleOf    ->
        pattern.elements
          .asReversed()
          .forEach { dropPattern(it, keeps) }
      is L.Pattern.Var        -> drop(eraseType(pattern.type).first(), keeps)
      is L.Pattern.Drop       -> eraseType(pattern.type).forEach { drop(it, keeps) }
    }
  }

  private fun matchPattern(
    scrutineer: L.Pattern,
  ) {
    +SetScore(REG_0, REG, 1)
    fun visit(
      scrutineer: L.Pattern,
    ) {
      when (scrutineer) {
        is L.Pattern.IntOf      -> {
          +Execute.StoreScore(
            RESULT, REG_1, REG,
            Execute.Run(
              GetData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.INT.id)(-1) }))
            )
          )
          drop(P.Stack.INT)
          +Execute.ConditionalScoreMatches(
            false, REG_1, REG, scrutineer.value..scrutineer.value,
            Execute.Run(
              SetScore(REG_0, REG, 0)
            )
          )
        }
        is L.Pattern.IntRangeOf -> {
          +Execute.StoreScore(
            RESULT, REG_1, REG,
            Execute.Run(
              GetData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.INT.id)(-1) }))
            )
          )
          drop(P.Stack.INT)
          +Execute.ConditionalScoreMatches(
            false, REG_1, REG, scrutineer.min..scrutineer.max,
            Execute.Run(
              SetScore(REG_0, REG, 0)
            )
          )
        }
        is L.Pattern.ListOf     -> TODO()
        is L.Pattern.CompoundOf -> TODO()
        is L.Pattern.TupleOf    ->
          scrutineer.elements
            .asReversed()
            .forEach { visit(it) } // TODO: short-circuit optimization (in lift phase?)
        is L.Pattern.Var        -> drop(eraseType(scrutineer.type).first())
        is L.Pattern.Drop       -> eraseType(scrutineer.type).forEach { drop(it) }
      }
    }
    visit(scrutineer)
    push(P.Stack.BYTE, SourceProvider.Value(Nbt.Byte(0)))
    +Execute.StoreStorage(
      RESULT, DataAccessor.Storage(MCX, nbtPath { it(P.Stack.BYTE.id)(-1) }), Type.BYTE, 1.0,
      Execute.Run(
        GetScore(REG_0, REG)
      )
    )
  }

  private fun eraseType(
    type: L.Type,
  ): List<P.Stack> {
    return when (type) {
      is L.Type.Bool      -> listOf(P.Stack.BYTE)
      is L.Type.Byte      -> listOf(P.Stack.BYTE)
      is L.Type.Short     -> listOf(P.Stack.SHORT)
      is L.Type.Int       -> listOf(P.Stack.INT)
      is L.Type.Long      -> listOf(P.Stack.LONG)
      is L.Type.Float     -> listOf(P.Stack.FLOAT)
      is L.Type.Double    -> listOf(P.Stack.DOUBLE)
      is L.Type.String    -> listOf(P.Stack.STRING)
      is L.Type.ByteArray -> listOf(P.Stack.BYTE_ARRAY)
      is L.Type.IntArray  -> listOf(P.Stack.INT_ARRAY)
      is L.Type.LongArray -> listOf(P.Stack.LONG_ARRAY)
      is L.Type.List      -> listOf(P.Stack.LIST)
      is L.Type.Compound  -> listOf(P.Stack.COMPOUND)
      is L.Type.Ref       -> listOf(P.Stack.INT)
      is L.Type.Tuple     -> type.elements.flatMap { eraseType(it) }
      is L.Type.Fun       -> listOf(P.Stack.COMPOUND)
      is L.Type.Union     -> type.elements
                               .firstOrNull()
                               ?.let { eraseType(it) } ?: listOf(P.Stack.END)
      is L.Type.Run       -> eraseType(type.body.value)
    }
  }

  private fun push(
    stack: P.Stack,
    source: SourceProvider?,
  ) {
    if (source != null && stack != P.Stack.END) {
      !{ Raw("# push ${stack.id}") }
      +ManipulateData(DataAccessor.Storage(MCX, nbtPath { it(stack.id) }), DataManipulator.Append(source))
    }
    entry(stack) += null
  }

  private fun drop(
    drop: P.Stack,
    keeps: List<P.Stack> = emptyList(),
    relevant: Boolean = true,
  ) {
    val index = -1 - keeps.count { it == drop }
    if (relevant && drop != P.Stack.END) {
      !{ Raw("# drop ${drop.id} under ${keeps.joinToString(", ", "[", "]") { it.id }}") }
      +RemoveData(DataAccessor.Storage(MCX, nbtPath { it(drop.id)(index) }))
    }
    val entry = entry(drop)
    entry.removeAt(entry.size + index)
  }

  private fun bind(
    level: Int,
    stack: P.Stack,
  ) {
    val entry = entry(stack)
    val index = entry.indexOfLast { it == null }
    entry[index] = level
  }

  operator fun get(
    level: Int,
    stack: P.Stack,
  ): Int {
    val entry = entry(stack)
    return entry
             .indexOfLast { it == level }
             .also { require(it != -1) } - entry.size
  }

  private fun entry(
    stack: P.Stack,
  ): MutableList<Int?> =
    entries[stack]!!

  @Suppress("NOTHING_TO_INLINE")
  private inline operator fun Command.unaryPlus() {
    commands += this
  }

  @Suppress("NOTHING_TO_INLINE")
  private inline operator fun (() -> Command).not() {
    if (context.config.debug) {
      +this()
    }
  }

  companion object {
    private val FREE: Packed.ScoreHolder = Packed.ScoreHolder("#free")
    private val REG_0: Packed.ScoreHolder = Packed.ScoreHolder("#0")
    private val REG_1: Packed.ScoreHolder = Packed.ScoreHolder("#1")
    private val REG: Packed.Objective = Packed.Objective("mcx")
    private val MCX: ResourceLocation = ResourceLocation("mcx", "")
    private val HEAP_SET: DataAccessor = DataAccessor.Storage(MCX, nbtPath { it("heap")(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)() })
    private val HEAP_GET: DataAccessor = DataAccessor.Storage(MCX, nbtPath { it("heap")(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(-2)(0) })
    private val DEALLOCATE: DefinitionLocation = ModuleLocation("heap") / "deallocate"
    private val TOUCH: DefinitionLocation = ModuleLocation("heap") / "touch"

    private fun packDefinitionLocation(
      location: DefinitionLocation,
    ): ResourceLocation =
      ResourceLocation("minecraft", (location.module.parts + escape(location.name)).joinToString("/"))

    private fun escape(
      string: String,
    ): String =
      string
        .encodeToByteArray()
        .joinToString("") {
          when (val char = it.toInt().toChar()) {
            in 'a'..'z', in '0'..'9', '_', '-' -> char.toString()
            else                               -> ".${it.toUByte().toString(Character.MAX_RADIX)}"
          }
        }

    // TODO: specialize dispatcher by type
    fun packDispatch(
      functions: List<L.Definition.Function>,
    ): P.Definition.Function {
      val name = packDefinitionLocation(DISPATCH)
      val commands = listOf(
        Execute.StoreScore(RESULT, REG_0, REG, Execute.Run(GetData(DataAccessor.Storage(MCX, nbtPath { it(P.Stack.COMPOUND.id)(-1)("_") }))))
      ) + functions.mapIndexed { index, function ->
        Execute.ConditionalScoreMatches(true, REG_0, REG, index..index, Execute.Run(RunFunction(packDefinitionLocation(function.name))))
      }
      return P.Definition.Function(name, commands)
    }

    operator fun invoke(
      context: Context,
      definition: L.Definition,
    ): P.Definition {
      return Pack(context).packDefinition(definition)
    }
  }
}
