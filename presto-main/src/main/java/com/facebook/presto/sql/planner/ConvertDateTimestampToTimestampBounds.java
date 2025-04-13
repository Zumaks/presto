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

public class ConvertDateTimestampToTimestampBounds
        implements Rule<FilterNode>
{
    private static final Pattern<FilterNode> PATTERN = filter();
    private static final Type BOOLEAN = BooleanType.BOOLEAN; // or however you reference it

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
        // Only handling binary comparison expressions
        if (!(expression instanceof CallExpression)) {
            return expression;
        }

        CallExpression call = (CallExpression) expression;

        // Proceed only for equality comparisons of two arguments
        if (!call.getDisplayName().equals(EQUAL.getFunctionName()) || call.getArguments().size() != 2) {
            return expression;
        }

        RowExpression left = call.getArguments().get(0);
        RowExpression right = call.getArguments().get(1);

        // --- Existing logic for date() and year() functions ---

        Optional<RowExpression> maybeDateCol = extractDateFunctionArgument(left, right);
        Optional<ConstantExpression> maybeDateLiteral = extractDateLiteral(left, right);

        if (maybeDateCol.isPresent() && maybeDateLiteral.isPresent()) {
            String dateValue = maybeDateLiteral.get().getValue().toString();
            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeDateCol.get(),
                    constant(dateValue + " 00:00:00.000", TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeDateCol.get(),
                    constant(dateValue + "+1 00:00:00.000", TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        Optional<RowExpression> maybeYearCol = extractYearFunctionArgument(left, right);
        Optional<ConstantExpression> maybeYearLiteral = extractYearLiteral(left, right);

        if (maybeYearCol.isPresent() && maybeYearLiteral.isPresent()) {
            Object literalVal = maybeYearLiteral.get().getValue();
            long year;
            if (literalVal instanceof Number) {
                year = ((Number) literalVal).longValue();
            } else {
                return expression;
            }

            String lowerTimestamp = String.format("%d-01-01 00:00:00.000", year);
            String upperTimestamp = String.format("%d-01-01 00:00:00.000", year + 1);

            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeYearCol.get(),
                    constant(lowerTimestamp, TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeYearCol.get(),
                    constant(upperTimestamp, TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // --- New logic for month() function ---
        Optional<RowExpression> maybeMonthCol = extractMonthFunctionArgument(left, right);
        Optional<ConstantExpression> maybeMonthLiteral = extractMonthLiteral(left, right);

        if (maybeMonthCol.isPresent() && maybeMonthLiteral.isPresent()) {
            // Assume literal is a string in "YYYY-MM" format.
            String monthValue = maybeMonthLiteral.get().getValue().toString();
            String[] parts = monthValue.split("-");
            if (parts.length != 2) {
                return expression;
            }
            int yearPart, monthPart;
            try {
                yearPart = Integer.parseInt(parts[0]);
                monthPart = Integer.parseInt(parts[1]);
            }
            catch (NumberFormatException e) {
                return expression;
            }
            if (monthPart < 1 || monthPart > 12) {
                return expression;
            }
            // Lower bound: first day of the month.
            String lowerTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart);
            // Upper bound: first day of the next month. Handle December specially.
            String upperTimestamp;
            if (monthPart == 12) {
                upperTimestamp = String.format("%d-01-01 00:00:00.000", yearPart + 1);
            }
            else {
                upperTimestamp = String.format("%d-%02d-01 00:00:00.000", yearPart, monthPart + 1);
            }

            RowExpression lowerBound = comparisonExpression(
                    functionResolution,
                    GREATER_THAN_OR_EQUAL,
                    maybeMonthCol.get(),
                    constant(lowerTimestamp, TIMESTAMP)
            );

            RowExpression upperBound = comparisonExpression(
                    functionResolution,
                    LESS_THAN,
                    maybeMonthCol.get(),
                    constant(upperTimestamp, TIMESTAMP)
            );

            return createAndExpression(lowerBound, upperBound);
        }

        // If none matched, return the expression unchanged.
        return expression;
    }

    private RowExpression createAndExpression(RowExpression left, RowExpression right)
    {
        FunctionHandle andHandle = functionResolution.lookupBuiltInFunction(
                "and",
                ImmutableList.of(BOOLEAN, BOOLEAN));

        // Create an 'and' call combining the two expressions.
        return new CallExpression(
                "and",
                andHandle,
                (Type) ImmutableList.of(left, right),
                (List<RowExpression>) BOOLEAN);
    }

    // --- Date Function Extraction (for date()) ---

    private Optional<RowExpression> extractDateFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractDateFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractDateFunction(second);
    }

    private Optional<ConstantExpression> extractDateLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantDateLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantDateLiteral(second);
    }

    private Optional<RowExpression> extractDateFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        boolean isDateFunction = call.getDisplayName().equalsIgnoreCase("date") &&
                call.getType().toString().equalsIgnoreCase("date");
        if (!isDateFunction || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantDateLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        ConstantExpression c = (ConstantExpression) expr;
        return Optional.of(c);
    }

    // --- Year Function Extraction (for year()) ---
    private Optional<RowExpression> extractYearFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractYearFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractYearFunction(second);
    }

    private Optional<ConstantExpression> extractYearLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantYearLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantYearLiteral(second);
    }

    private Optional<RowExpression> extractYearFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        if (!call.getDisplayName().equalsIgnoreCase("year") || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantYearLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        ConstantExpression c = (ConstantExpression) expr;
        Object value = c.getValue();
        if (value instanceof Number) {
            return Optional.of(c);
        }
        return Optional.empty();
    }

    // --- Month Function Extraction (for month()) ---

    private Optional<RowExpression> extractMonthFunctionArgument(RowExpression first, RowExpression second)
    {
        Optional<RowExpression> firstCheck = extractMonthFunction(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractMonthFunction(second);
    }

    private Optional<ConstantExpression> extractMonthLiteral(RowExpression first, RowExpression second)
    {
        Optional<ConstantExpression> firstCheck = extractConstantMonthLiteral(first);
        if (firstCheck.isPresent()) {
            return firstCheck;
        }
        return extractConstantMonthLiteral(second);
    }

    private Optional<RowExpression> extractMonthFunction(RowExpression expr)
    {
        if (!(expr instanceof CallExpression)) {
            return Optional.empty();
        }
        CallExpression call = (CallExpression) expr;
        if (!call.getDisplayName().equalsIgnoreCase("month") || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(call.getArguments().get(0));
    }

    private Optional<ConstantExpression> extractConstantMonthLiteral(RowExpression expr)
    {
        if (!(expr instanceof ConstantExpression)) {
            return Optional.empty();
        }
        ConstantExpression c = (ConstantExpression) expr;
        // Assume the constant’s value is a string literal in the format "YYYY-MM".
        return Optional.of(c);
    }
}

