package com.facebook.presto.sql.planner;

import com.facebook.presto.common.type.BooleanType;
import com.facebook.presto.common.type.Type;
import com.facebook.presto.hive.$internal.com.google.common.collect.ImmutableList;
import com.facebook.presto.metadata.FunctionAndTypeManager;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.function.FunctionHandle;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.sql.analyzer.FunctionAndTypeResolver;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.relational.FunctionResolution;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.common.function.OperatorType.EQUAL;
import static com.facebook.presto.common.function.OperatorType.GREATER_THAN_OR_EQUAL;
import static com.facebook.presto.common.function.OperatorType.LESS_THAN;
import static com.facebook.presto.common.type.TimestampType.TIMESTAMP;
import static com.facebook.presto.sql.planner.plan.Patterns.filter;
import static com.facebook.presto.sql.relational.Expressions.comparisonExpression;
import static com.facebook.presto.sql.relational.Expressions.constant;

/**
 * Rule to unwrap date functions in comparisons with literals
 * Transforms:
 *   date(ts_col) = DATE 'yyyy-mm-dd'
 * to:
 *   ts_col >= TIMESTAMP 'yyyy-mm-dd 00:00:00.000' AND
 *   ts_col < TIMESTAMP 'yyyy-mm-dd+1 00:00:00.000'
 */
public class ConvertDateTimestampToTimestampBounds
        implements Rule<FilterNode>
{
    private static final Pattern<FilterNode> PATTERN = filter();

    private final FunctionAndTypeManager functionAndTypeManager;
    private final StandardFunctionResolution functionResolution;

    public ConvertDateTimestampToTimestampBounds(FunctionAndTypeManager functionAndTypeManager)
    {
        this.functionAndTypeManager = functionAndTypeManager;
        this.functionResolution = new FunctionResolution((FunctionAndTypeResolver) functionAndTypeManager);
    }

    @Override
    public Pattern<FilterNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(FilterNode filter, Captures captures, Context context)
    {
        RowExpression originalPredicate = filter.getPredicate();
        RowExpression rewritten = rewritePredicate(originalPredicate);

        if (rewritten.equals(originalPredicate)) {
            return Result.empty();
        }

        FilterNode newFilter = new FilterNode(
                filter.getSourceLocation(),
                filter.getId(),
                filter.getSource(),
                rewritten);
        return Result.ofPlanNode(newFilter);
    }

    private RowExpression rewritePredicate(RowExpression expression)
    {
        // If not a function call, do nothing
        if (!(expression instanceof CallExpression)) {
            return expression;
        }

        CallExpression call = (CallExpression) expression;

        // Check for equals operation
        if (!call.getDisplayName().equals(EQUAL.getFunctionName()) ||
                call.getArguments().size() != 2) {
            return expression;
        }

        RowExpression left = call.getArguments().get(0);
        RowExpression right = call.getArguments().get(1);

        // Try to extract date function argument and date literal in both orders
        Optional<RowExpression> maybeCol = extractDateFunctionArgument(left, right);
        Optional<ConstantExpression> maybeDate = extractDateLiteral(left, right);

        if (maybeCol.isPresent() && maybeDate.isPresent()) {
            // Create lower and upper bound timestamp expressions
            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeCol.get(),
                    constant(maybeDate.get().getValue() + " 00:00:00.000", TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeCol.get(),
                    constant(maybeDate.get().getValue() + "+1 00:00:00.000", TIMESTAMP)
            );

            // Create AND expression combining lower and upper bounds
            return createAndExpression(lowerBound, upperBound);
        }

        // If not recognized, leave it alone
        return expression;
    }

    private static final Type BOOLEAN = BooleanType.BOOLEAN; // or however you reference it

    private RowExpression createAndExpression(RowExpression left, RowExpression right)
    {
        FunctionHandle andHandle = functionResolution.lookupBuiltInFunction(
                "and",
                ImmutableList.of(BOOLEAN, BOOLEAN));

        return new CallExpression(
                "and",
                andHandle,
                (Type) ImmutableList.of(left, right),
                (List<RowExpression>) BOOLEAN);
    }

    private Optional<RowExpression> extractDateFunctionArgument(RowExpression first, RowExpression second)
    {
        // Check for date function on first argument
        Optional<RowExpression> firstCheck = extractDateFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }

        // Check for date function on second argument
        return extractDateFunction(second);
    }

    private Optional<ConstantExpression> extractDateLiteral(RowExpression first, RowExpression second)
    {
        // Try to extract date from first argument
        Optional<ConstantExpression> firstDateCheck = extractConstantDateLiteral(first);
        if (firstDateCheck.isPresent()) {
            return firstDateCheck;
        }

        // Try to extract date from second argument
        return extractConstantDateLiteral(second);
    }

    private Optional<RowExpression> extractDateFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }

        CallExpression call = (CallExpression) expr;

        // Check for date function (could be expanded for other date-related functions)
        boolean isDateFunction = call.getDisplayName().equalsIgnoreCase("date") &&
                call.getType().toString().equalsIgnoreCase("date");

        if (!isDateFunction || call.getArguments().isEmpty()) {
            return Optional.empty();
        }

        // Return the underlying timestamp expression
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantDateLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }

        ConstantExpression c = (ConstantExpression) expr;

        // Directly return the ConstantExpression without parsing
        return Optional.of(c);
    }
}