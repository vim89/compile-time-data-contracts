package org.apache.spark.sql.ctdcprobe

import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{DataType, StructType}

/** Access to the schema-comparison predicates Spark does not export.
  *
  * `DataType.equalsIgnoreNullability`, `equalsIgnoreCaseAndNullability` and `equalsStructurally` are reachable from
  * ordinary code, but `sameType` is `private[spark]` and `equalsIgnoreCompatibleNullability` is `private[sql]`, so a
  * characterisation study of the whole family cannot be written outside Spark's own package. This file is the smallest
  * thing that closes that gap: it lives in a sub-package of `org.apache.spark.sql`, which satisfies both qualifiers,
  * and it forwards without adding behaviour of its own, so a reader can see that nothing is being measured except
  * Spark.
  *
  * Nothing in ctdc's published API depends on this. It exists for `ctdc.probe.ComparatorMatrix`.
  */
object SparkPrivateComparators:

  /** `DataType.sameType`: structural equality that ignores nullability at every level. */
  def sameType(found: StructType, expected: StructType): Boolean =
    found.sameType(expected)

  /** `DataType.equalsIgnoreCompatibleNullability`: asymmetric, so the argument order is named rather than positional.
    * Spark reads it as "can a value of `from` be read as `to`", i.e. `to` may relax a `from` that is already stricter.
    */
  def equalsIgnoreCompatibleNullability(from: StructType, to: StructType): Boolean =
    DataType.equalsIgnoreCompatibleNullability(from, to)

  /** `sameType` evaluated under an explicit `spark.sql.caseSensitive`, to establish whether its verdict is a function
    * of its arguments alone. `SQLConf.withExistingConf` installs a conf on the calling thread for the duration of the
    * block, which is how Spark's own tests vary this without starting a session.
    */
  def sameTypeUnderCaseSensitivity(found: StructType, expected: StructType, caseSensitive: Boolean): Boolean =
    val conf = new SQLConf
    conf.setConfString("spark.sql.caseSensitive", caseSensitive.toString)
    SQLConf.withExistingConf(conf)(found.sameType(expected))
