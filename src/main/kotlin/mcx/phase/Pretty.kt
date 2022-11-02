package mcx.phase

import mcx.ast.Core as C

fun prettyType0(type: C.Type0): String {
  return when (type) {
    is C.Type0.Int    -> "int"
    is C.Type0.String -> "string"
    is C.Type0.Ref    -> "ref[${prettyType0(type.element)}]"
    is C.Type0.Hole   -> "?"
  }
}