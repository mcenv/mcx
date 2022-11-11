package mcx.ast

object Packed {
  data class Root(
    val module: Location,
    val resources: List<Resource>,
  )

  sealed interface Resource {
    val module: Location
    val name: String

    data class JsonResource(
      val registry: Registry,
      override val module: Location,
      override val name: String,
      val body: Json,
    ) : Resource

    data class Function(
      override val module: Location,
      override val name: String,
      val instructions: List<Instruction>,
    ) : Resource
  }

  enum class Type {
    BYTE,
    INT,
    FLOAT,
    STRING,
    LIST,
    COMPOUND,
  }

  sealed interface Instruction {
    data class Push(
      val tag: Tag,
    ) : Instruction

    data class Copy(
      val index: Int,
      val type: Type,
    ) : Instruction

    data class Drop(
      val index: Int,
      val type: Type,
    ) : Instruction

    data class Run(
      val module: Location,
      val name: String,
    ) : Instruction

    data class Debug(
      val message: String,
    ) : Instruction
  }

  sealed interface Tag {
    val type: Type

    data class ByteOf(val value: Byte) : Tag {
      override val type: Type get() = Type.BYTE
    }

    data class IntOf(val value: Int) : Tag {
      override val type: Type get() = Type.INT
    }

    data class FloatOf(val value: Float) : Tag {
      override val type: Type get() = Type.FLOAT
    }

    data class StringOf(val value: String) : Tag {
      override val type: Type get() = Type.STRING
    }
  }
}
