/**
 * Compile-time structural data contracts.
 *
 * Proves, while compiling, that a producer type `Out` conforms to a declared `Contract` under a policy `P`.
 *
 * Key types:
 *   - [[ctdc.SchemaConforms]] - evidence that `Out` conforms to `Contract` under `P`.
 *   - [[ctdc.SchemaPolicy]] - how conformance is checked: exact, ordered, by position, backward, forward.
 *
 * {{{
 * import ctdc._
 *
 * final case class V1(id: Long, email: String)
 * final case class V2(id: Long, email: String, age: Int)
 *
 * // Backward-compatible: the producer may add fields.
 * conforms[V2, V1, SchemaPolicy.Backward]
 * }}}
 *
 * A failure is a compile error carrying the missing, extra and mismatched fields with their paths.
 */
package object ctdc {

  /**
   * Ask for contract evidence at a point of your choosing.
   *
   * Equivalent to `implicitly`, but named for what it proves, so a contract can be asserted where it is
   * declared rather than only where some method happens to require it.
   */
  def conforms[Out, Contract, P <: SchemaPolicy](implicit
    ev: SchemaConforms[Out, Contract, P]
  ): SchemaConforms[Out, Contract, P] = ev
}
