package ctdc

/** Scala 3 materializer for [[SchemaConforms]]. Mixed into `object SchemaConforms`. */
trait SchemaConformsMaterializer {

  /**
   * Materialize compile-time evidence that Out conforms to Contract under policy P.
   *
   * The macro builds a `TypeShape` for each side and compares them under the policy. No runtime overhead -
   * pure compile-time validation.
   */
  inline given materialize[Out, Contract, P <: SchemaPolicy]: SchemaConforms[Out, Contract, P] =
    ${ internal.ContractMacros.conformsImpl[Out, Contract, P] }
}
