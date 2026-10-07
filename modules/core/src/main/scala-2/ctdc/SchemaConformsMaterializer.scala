package ctdc

import scala.language.experimental.macros

/** Scala 2 materializer for [[SchemaConforms]]. Mixed into `object SchemaConforms`. */
trait SchemaConformsMaterializer {

  /**
   * Materialize compile-time evidence that Out conforms to Contract under policy P.
   *
   * The macro builds a `TypeShape` for each side and compares them under the policy. No runtime overhead -
   * pure compile-time validation.
   */
  implicit def materialize[Out, Contract, P <: SchemaPolicy]: SchemaConforms[Out, Contract, P] =
    macro internal.ContractMacros.conformsImpl[Out, Contract, P]
}
