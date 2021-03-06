/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{AggregateExpression2, Utils}
import org.apache.spark.sql.catalyst.planning._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical.{BroadcastHint, LogicalPlan}
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.columnar.{InMemoryColumnarTableScan, InMemoryRelation}
import org.apache.spark.sql.execution.datasources.{CreateTableUsing, CreateTempTableUsing, DescribeCommand => LogicalDescribeCommand, _}
import org.apache.spark.sql.execution.{DescribeCommand => RunnableDescribeCommand}
import org.apache.spark.sql.{Strategy, execution}

private[sql] abstract class SparkStrategies extends QueryPlanner[SparkPlan] {
  self: SparkPlanner =>

  object LeftSemiJoin extends Strategy with PredicateHelper {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case ExtractEquiJoinKeys(
             LeftSemi, leftKeys, rightKeys, condition, left, CanBroadcast(right)) =>
        joins.BroadcastLeftSemiJoinHash(
          leftKeys, rightKeys, planLater(left), planLater(right), condition) :: Nil
      // Find left semi joins where at least some predicates can be evaluated by matching join keys
      case ExtractEquiJoinKeys(LeftSemi, leftKeys, rightKeys, condition, left, right) =>
        joins.LeftSemiJoinHash(
          leftKeys, rightKeys, planLater(left), planLater(right), condition) :: Nil
      // no predicate can be evaluated by matching hash keys
      case logical.Join(left, right, LeftSemi, condition) =>
        joins.LeftSemiJoinBNL(planLater(left), planLater(right), condition) :: Nil
      case _ => Nil
    }
  }

  /**
   * Matches a plan whose output should be small enough to be used in broadcast join.
   */
  object CanBroadcast {
    def unapply(plan: LogicalPlan): Option[LogicalPlan] = plan match {
      case BroadcastHint(p) => Some(p)
      case p if sqlContext.conf.autoBroadcastJoinThreshold > 0 &&
        p.statistics.sizeInBytes <= sqlContext.conf.autoBroadcastJoinThreshold => Some(p)
      case _ => None
    }
  }

  /**
   * Uses the [[ExtractEquiJoinKeys]] pattern to find joins where at least some of the predicates
   * can be evaluated by matching join keys.
   *
   * Join implementations are chosen with the following precedence:
   *
   * - Broadcast: if one side of the join has an estimated physical size that is smaller than the
   *     user-configurable [[org.apache.spark.sql.SQLConf.AUTO_BROADCASTJOIN_THRESHOLD]] threshold
   *     or if that side has an explicit broadcast hint (e.g. the user applied the
   *     [[org.apache.spark.sql.functions.broadcast()]] function to a DataFrame), then that side
   *     of the join will be broadcasted and the other side will be streamed, with no shuffling
   *     performed. If both sides of the join are eligible to be broadcasted then the
   * - Sort merge: if the matching join keys are sortable and
   *     [[org.apache.spark.sql.SQLConf.SORTMERGE_JOIN]] is enabled (default), then sort merge join
   *     will be used.
   * - Hash: will be chosen if neither of the above optimizations apply to this join.
   */
  object EquiJoinSelection extends Strategy with PredicateHelper {

    private[this] def makeBroadcastHashJoin(
        leftKeys: Seq[Expression],
        rightKeys: Seq[Expression],
        left: LogicalPlan,
        right: LogicalPlan,
        condition: Option[Expression],
        side: joins.BuildSide): Seq[SparkPlan] = {
      val broadcastHashJoin = execution.joins.BroadcastHashJoin(
        leftKeys, rightKeys, side, planLater(left), planLater(right))
      condition.map(Filter(_, broadcastHashJoin)).getOrElse(broadcastHashJoin) :: Nil
    }

    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {

      // --- Inner joins --------------------------------------------------------------------------

      case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, left, CanBroadcast(right)) =>
        makeBroadcastHashJoin(leftKeys, rightKeys, left, right, condition, joins.BuildRight)

      case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, CanBroadcast(left), right) =>
        makeBroadcastHashJoin(leftKeys, rightKeys, left, right, condition, joins.BuildLeft)

      case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, left, right)
        if sqlContext.conf.sortMergeJoinEnabled && RowOrdering.isOrderable(leftKeys) =>
        val mergeJoin =
          joins.SortMergeJoin(leftKeys, rightKeys, planLater(left), planLater(right))
        condition.map(Filter(_, mergeJoin)).getOrElse(mergeJoin) :: Nil

      case ExtractEquiJoinKeys(Inner, leftKeys, rightKeys, condition, left, right) =>
        val buildSide =
          if (right.statistics.sizeInBytes <= left.statistics.sizeInBytes) {
            joins.BuildRight
          } else {
            joins.BuildLeft
          }
        val hashJoin = joins.ShuffledHashJoin(
          leftKeys, rightKeys, buildSide, planLater(left), planLater(right))
        condition.map(Filter(_, hashJoin)).getOrElse(hashJoin) :: Nil

      // --- Outer joins --------------------------------------------------------------------------

      case ExtractEquiJoinKeys(
          LeftOuter, leftKeys, rightKeys, condition, left, CanBroadcast(right)) =>
        joins.BroadcastHashOuterJoin(
          leftKeys, rightKeys, LeftOuter, condition, planLater(left), planLater(right)) :: Nil

      case ExtractEquiJoinKeys(
          RightOuter, leftKeys, rightKeys, condition, CanBroadcast(left), right) =>
        joins.BroadcastHashOuterJoin(
          leftKeys, rightKeys, RightOuter, condition, planLater(left), planLater(right)) :: Nil

      case ExtractEquiJoinKeys(joinType, leftKeys, rightKeys, condition, left, right)
        if sqlContext.conf.sortMergeJoinEnabled && RowOrdering.isOrderable(leftKeys) =>
        joins.SortMergeOuterJoin(
          leftKeys, rightKeys, joinType, condition, planLater(left), planLater(right)) :: Nil

      case ExtractEquiJoinKeys(joinType, leftKeys, rightKeys, condition, left, right) =>
        joins.ShuffledHashOuterJoin(
          leftKeys, rightKeys, joinType, condition, planLater(left), planLater(right)) :: Nil

      // --- Cases where this strategy does not apply ---------------------------------------------

      case _ => Nil
    }
  }

  object HashAggregation extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      // Aggregations that can be performed in two phases, before and after the shuffle.
      case PartialAggregation(
          namedGroupingAttributes,
          rewrittenAggregateExpressions,
          groupingExpressions,
          partialComputation,
          child) if !canBeConvertedToNewAggregation(plan) =>
        execution.Aggregate(
          partial = false,
          namedGroupingAttributes,
          rewrittenAggregateExpressions,
          execution.Aggregate(
            partial = true,
            groupingExpressions,
            partialComputation,
            planLater(child))) :: Nil

      case _ => Nil
    }

    def canBeConvertedToNewAggregation(plan: LogicalPlan): Boolean = plan match {
      case a: logical.Aggregate =>
        if (sqlContext.conf.useSqlAggregate2 && sqlContext.conf.codegenEnabled) {
          a.newAggregation.isDefined
        } else {
          Utils.checkInvalidAggregateFunction2(a)
          false
        }
      case _ => false
    }

    def allAggregates(exprs: Seq[Expression]): Seq[AggregateExpression1] =
      exprs.flatMap(_.collect { case a: AggregateExpression1 => a })
  }

  /**
   * Used to plan the aggregate operator for expressions based on the AggregateFunction2 interface.
   */
  object Aggregation extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case p: logical.Aggregate if sqlContext.conf.useSqlAggregate2 &&
          sqlContext.conf.codegenEnabled =>
        val converted = p.newAggregation
        converted match {
          case None => Nil // Cannot convert to new aggregation code path.
          case Some(logical.Aggregate(groupingExpressions, resultExpressions, child)) =>
            // A single aggregate expression might appear multiple times in resultExpressions.
            // In order to avoid evaluating an individual aggregate function multiple times, we'll
            // build a set of the distinct aggregate expressions and build a function which can
            // be used to re-write expressions so that they reference the single copy of the
            // aggregate function which actually gets computed.
            val aggregateExpressions = resultExpressions.flatMap { expr =>
              expr.collect {
                case agg: AggregateExpression2 => agg
              }
            }.distinct
            // For those distinct aggregate expressions, we create a map from the
            // aggregate function to the corresponding attribute of the function.
            val aggregateFunctionToAttribute = aggregateExpressions.map { agg =>
              val aggregateFunction = agg.aggregateFunction
              val attribute = Alias(aggregateFunction, aggregateFunction.toString)().toAttribute
              (aggregateFunction, agg.isDistinct) -> attribute
            }.toMap

            val (functionsWithDistinct, functionsWithoutDistinct) =
              aggregateExpressions.partition(_.isDistinct)
            if (functionsWithDistinct.map(_.aggregateFunction.children).distinct.length > 1) {
              // This is a sanity check. We should not reach here when we have multiple distinct
              // column sets (aggregate.NewAggregation will not match).
              sys.error(
                "Multiple distinct column sets are not supported by the new aggregation" +
                  "code path.")
            }

            val namedGroupingExpressions = groupingExpressions.map {
              case ne: NamedExpression => ne -> ne
              // If the expression is not a NamedExpressions, we add an alias.
              // So, when we generate the result of the operator, the Aggregate Operator
              // can directly get the Seq of attributes representing the grouping expressions.
              case other =>
                val withAlias = Alias(other, other.toString)()
                other -> withAlias
            }
            val groupExpressionMap = namedGroupingExpressions.toMap

            // The original `resultExpressions` are a set of expressions which may reference
            // aggregate expressions, grouping column values, and constants. When aggregate operator
            // emits output rows, we will use `resultExpressions` to generate an output projection
            // which takes the grouping columns and final aggregate result buffer as input.
            // Thus, we must re-write the result expressions so that their attributes match up with
            // the attributes of the final result projection's input row:
            val rewrittenResultExpressions = resultExpressions.map { expr =>
              expr.transformDown {
                case AggregateExpression2(aggregateFunction, _, isDistinct) =>
                  // The final aggregation buffer's attributes will be `finalAggregationAttributes`,
                  // so replace each aggregate expression by its corresponding attribute in the set:
                  aggregateFunctionToAttribute(aggregateFunction, isDistinct)
                case expression =>
                  // Since we're using `namedGroupingAttributes` to extract the grouping key
                  // columns, we need to replace grouping key expressions with their corresponding
                  // attributes. We do not rely on the equality check at here since attributes may
                  // differ cosmetically. Instead, we use semanticEquals.
                  groupExpressionMap.collectFirst {
                    case (expr, ne) if expr semanticEquals expression => ne.toAttribute
                  }.getOrElse(expression)
              }.asInstanceOf[NamedExpression]
            }

            val aggregateOperator =
              if (aggregateExpressions.map(_.aggregateFunction).exists(!_.supportsPartial)) {
                if (functionsWithDistinct.nonEmpty) {
                  sys.error("Distinct columns cannot exist in Aggregate operator containing " +
                    "aggregate functions which don't support partial aggregation.")
                } else {
                  aggregate.Utils.planAggregateWithoutPartial(
                    namedGroupingExpressions.map(_._2),
                    aggregateExpressions,
                    aggregateFunctionToAttribute,
                    rewrittenResultExpressions,
                    planLater(child))
                }
              } else if (functionsWithDistinct.isEmpty) {
                aggregate.Utils.planAggregateWithoutDistinct(
                  namedGroupingExpressions.map(_._2),
                  aggregateExpressions,
                  aggregateFunctionToAttribute,
                  rewrittenResultExpressions,
                  planLater(child))
              } else {
                aggregate.Utils.planAggregateWithOneDistinct(
                  namedGroupingExpressions.map(_._2),
                  functionsWithDistinct,
                  functionsWithoutDistinct,
                  aggregateFunctionToAttribute,
                  rewrittenResultExpressions,
                  planLater(child))
              }

            aggregateOperator
        }

      case _ => Nil
    }
  }

  object BroadcastNestedLoop extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case logical.Join(
             CanBroadcast(left), right, joinType, condition) if joinType != LeftSemi =>
        execution.joins.BroadcastNestedLoopJoin(
          planLater(left), planLater(right), joins.BuildLeft, joinType, condition) :: Nil
      case logical.Join(
             left, CanBroadcast(right), joinType, condition) if joinType != LeftSemi =>
        execution.joins.BroadcastNestedLoopJoin(
          planLater(left), planLater(right), joins.BuildRight, joinType, condition) :: Nil
      case _ => Nil
    }
  }

  object CartesianProduct extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      // TODO CartesianProduct doesn't support the Left Semi Join
      case logical.Join(left, right, joinType, None) if joinType != LeftSemi =>
        execution.joins.CartesianProduct(planLater(left), planLater(right)) :: Nil
      case logical.Join(left, right, Inner, Some(condition)) =>
        execution.Filter(condition,
          execution.joins.CartesianProduct(planLater(left), planLater(right))) :: Nil
      case _ => Nil
    }
  }

  object DefaultJoin extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case logical.Join(left, right, joinType, condition) =>
        val buildSide =
          if (right.statistics.sizeInBytes <= left.statistics.sizeInBytes) {
            joins.BuildRight
          } else {
            joins.BuildLeft
          }
        joins.BroadcastNestedLoopJoin(
          planLater(left), planLater(right), buildSide, joinType, condition) :: Nil
      case _ => Nil
    }
  }

  protected lazy val singleRowRdd = sparkContext.parallelize(Seq(InternalRow()), 1)

  object TakeOrderedAndProject extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case logical.Limit(IntegerLiteral(limit), logical.Sort(order, true, child)) =>
        execution.TakeOrderedAndProject(limit, order, None, planLater(child)) :: Nil
      case logical.Limit(
             IntegerLiteral(limit),
             logical.Project(projectList, logical.Sort(order, true, child))) =>
        execution.TakeOrderedAndProject(limit, order, Some(projectList), planLater(child)) :: Nil
      case _ => Nil
    }
  }

  object InMemoryScans extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case PhysicalOperation(projectList, filters, mem: InMemoryRelation) =>
        pruneFilterProject(
          projectList,
          filters,
          identity[Seq[Expression]], // All filters still need to be evaluated.
          InMemoryColumnarTableScan(_, filters, mem)) :: Nil
      case _ => Nil
    }
  }

  // Can we automate these 'pass through' operations?
  object BasicOperators extends Strategy {
    def numPartitions: Int = self.numPartitions

    /**
     * Picks an appropriate sort operator.
     *
     * @param global when true performs a global sort of all partitions by shuffling the data first
     *               if necessary.
     */
    def getSortOperator(sortExprs: Seq[SortOrder], global: Boolean, child: SparkPlan): SparkPlan = {
      if (sqlContext.conf.unsafeEnabled && sqlContext.conf.codegenEnabled &&
        TungstenSort.supportsSchema(child.schema)) {
        execution.TungstenSort(sortExprs, global, child)
      } else {
        execution.Sort(sortExprs, global, child)
      }
    }

    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case r: RunnableCommand => ExecutedCommand(r) :: Nil

      case logical.Distinct(child) =>
        throw new IllegalStateException(
          "logical distinct operator should have been replaced by aggregate in the optimizer")

      case logical.MapPartitions(f, tEnc, uEnc, output, child) =>
        execution.MapPartitions(f, tEnc, uEnc, output, planLater(child)) :: Nil
      case logical.AppendColumn(f, tEnc, uEnc, newCol, child) =>
        execution.AppendColumns(f, tEnc, uEnc, newCol, planLater(child)) :: Nil
      case logical.MapGroups(f, kEnc, tEnc, uEnc, grouping, output, child) =>
        execution.MapGroups(f, kEnc, tEnc, uEnc, grouping, output, planLater(child)) :: Nil
      case logical.CoGroup(f, kEnc, leftEnc, rightEnc, rEnc, output,
        leftGroup, rightGroup, left, right) =>
        execution.CoGroup(f, kEnc, leftEnc, rightEnc, rEnc, output, leftGroup, rightGroup,
          planLater(left), planLater(right)) :: Nil

      case logical.Repartition(numPartitions, shuffle, child) =>
        if (shuffle) {
          execution.Exchange(RoundRobinPartitioning(numPartitions), planLater(child)) :: Nil
        } else {
          execution.Coalesce(numPartitions, planLater(child)) :: Nil
        }
      case logical.SortPartitions(sortExprs, child) =>
        // This sort only sorts tuples within a partition. Its requiredDistribution will be
        // an UnspecifiedDistribution.
        getSortOperator(sortExprs, global = false, planLater(child)) :: Nil
      case logical.Sort(sortExprs, global, child) =>
        getSortOperator(sortExprs, global, planLater(child)):: Nil
      case logical.Project(projectList, child) =>
        // If unsafe mode is enabled and we support these data types in Unsafe, use the
        // Tungsten project. Otherwise, use the normal project.
        if (sqlContext.conf.unsafeEnabled &&
          UnsafeProjection.canSupport(projectList) && UnsafeProjection.canSupport(child.schema)) {
          execution.TungstenProject(projectList, planLater(child)) :: Nil
        } else {
          execution.Project(projectList, planLater(child)) :: Nil
        }
      case logical.Filter(condition, child) =>
        execution.Filter(condition, planLater(child)) :: Nil
      case e @ logical.Expand(_, _, child) =>
        execution.Expand(e.projections, e.output, planLater(child)) :: Nil
      case a @ logical.Aggregate(group, agg, child) => {
        val useNewAggregation = sqlContext.conf.useSqlAggregate2 && sqlContext.conf.codegenEnabled
        if (useNewAggregation && a.newAggregation.isDefined) {
          // If this logical.Aggregate can be planned to use new aggregation code path
          // (i.e. it can be planned by the Strategy Aggregation), we will not use the old
          // aggregation code path.
          Nil
        } else {
          Utils.checkInvalidAggregateFunction2(a)
          execution.Aggregate(partial = false, group, agg, planLater(child)) :: Nil
        }
      }
      case logical.Window(projectList, windowExprs, partitionSpec, orderSpec, child) =>
        execution.Window(
          projectList, windowExprs, partitionSpec, orderSpec, planLater(child)) :: Nil
      case logical.Sample(lb, ub, withReplacement, seed, child) =>
        execution.Sample(lb, ub, withReplacement, seed, planLater(child)) :: Nil
      case logical.LocalRelation(output, data) =>
        LocalTableScan(output, data) :: Nil
      case logical.Limit(IntegerLiteral(limit), child) =>
        execution.Limit(limit, planLater(child)) :: Nil
      case Unions(unionChildren) =>
        execution.Union(unionChildren.map(planLater)) :: Nil
      case logical.Except(left, right) =>
        execution.Except(planLater(left), planLater(right)) :: Nil
      case logical.Intersect(left, right) =>
        execution.Intersect(planLater(left), planLater(right)) :: Nil
      case g @ logical.Generate(generator, join, outer, _, _, child) =>
        execution.Generate(
          generator, join = join, outer = outer, g.output, planLater(child)) :: Nil
      case logical.OneRowRelation =>
        execution.PhysicalRDD(Nil, singleRowRdd, "OneRowRelation") :: Nil
      case logical.RepartitionByExpression(expressions, child, nPartitions) =>
        execution.Exchange(HashPartitioning(
          expressions, nPartitions.getOrElse(numPartitions)), planLater(child)) :: Nil
      case e @ EvaluatePython(udf, child, _) =>
        BatchPythonEvaluation(udf, e.output, planLater(child)) :: Nil
      case LogicalRDD(output, rdd) => PhysicalRDD(output, rdd, "PhysicalRDD") :: Nil
      case BroadcastHint(child) => apply(child)
      case _ => Nil
    }
  }

  object DDLStrategy extends Strategy {
    def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
      case CreateTableUsing(tableIdent, userSpecifiedSchema, provider, true, opts, false, _) =>
        ExecutedCommand(
          CreateTempTableUsing(
            tableIdent, userSpecifiedSchema, provider, opts)) :: Nil
      case c: CreateTableUsing if !c.temporary =>
        sys.error("Tables created with SQLContext must be TEMPORARY. Use a HiveContext instead.")
      case c: CreateTableUsing if c.temporary && c.allowExisting =>
        sys.error("allowExisting should be set to false when creating a temporary table.")

      case CreateTableUsingAsSelect(tableIdent, provider, true, partitionsCols, mode, opts, query)
          if partitionsCols.nonEmpty =>
        sys.error("Cannot create temporary partitioned table.")

      case CreateTableUsingAsSelect(tableIdent, provider, true, _, mode, opts, query) =>
        val cmd = CreateTempTableUsingAsSelect(
          tableIdent, provider, Array.empty[String], mode, opts, query)
        ExecutedCommand(cmd) :: Nil
      case c: CreateTableUsingAsSelect if !c.temporary =>
        sys.error("Tables created with SQLContext must be TEMPORARY. Use a HiveContext instead.")

      case describe @ LogicalDescribeCommand(table, isExtended) =>
        val resultPlan = self.sqlContext.executePlan(table).executedPlan
        ExecutedCommand(
          RunnableDescribeCommand(resultPlan, describe.output, isExtended)) :: Nil

      case logical.ShowFunctions(db, pattern) => ExecutedCommand(ShowFunctions(db, pattern)) :: Nil

      case logical.DescribeFunction(function, extended) =>
        ExecutedCommand(DescribeFunction(function, extended)) :: Nil

      case _ => Nil
    }
  }
}
